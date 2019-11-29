package fi.geniem.gdpr.personaldataflow;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;

@SupportedAnnotationTypes({"*",})
public class PersonalDataMetricsProcessor extends AbstractProcessor implements TaskListener{
		
	private Trees trees;
	private TaskEvent taskEvt;
	private Messager messager;

    private Map<String, Set<String>> methodDependencies;
    private Map<String, Set<String>> methodPersonalData;

    @Override
	public synchronized void init(ProcessingEnvironment processingEnv) {
		super.init(processingEnv);
		trees = Trees.instance(processingEnv);
		messager = processingEnv.getMessager();
	    JavacTask.instance(processingEnv).setTaskListener(this);
        methodDependencies = new HashMap<>();
        methodPersonalData = new HashMap<>();
    }
	
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        return false;
    }

    private boolean isPersonalData(Element field){
        return hasPersonalDataAnnotation(field) || isFieldTypePersonalData(field);
    }

    private boolean isDatabaseEntity(Element field) {
        return hasEntityAnnotation(field) || isFieldTypeEntity(field);
    }

    private boolean isFieldTypePersonalData(Element field){
    	if(field == null){
    		return false;
    	}

    	Element type = processingEnv.getTypeUtils().asElement(field.asType());

    	if (type == null || !TypeElement.class.isAssignableFrom(type.getClass())) {
    	    return false;
        }

        TypeElement fieldTypeElement = (TypeElement) type;
        return hasPersonalDataAnnotation(fieldTypeElement) || superClassIsPersonalData(fieldTypeElement);
    }

    private boolean isFieldTypeEntity(Element field){
        if(field == null){
            return false;
        }

        Element type = processingEnv.getTypeUtils().asElement(field.asType());

        if (type == null || !TypeElement.class.isAssignableFrom(type.getClass())) {
            return false;
        }

        TypeElement fieldTypeElement = (TypeElement) type;
        return hasEntityAnnotation(fieldTypeElement) || superClassIsEntity(fieldTypeElement);
    }

    private boolean superClassIsPersonalData(TypeElement ele){
        if(ele == null){
            return false;
        }

        Element type = processingEnv.getTypeUtils().asElement(ele.getSuperclass());

        if (type == null || !TypeElement.class.isAssignableFrom(type.getClass())) {
            return false;
        }

        TypeElement superType = (TypeElement) type;
        return hasPersonalDataAnnotation(superType) || superClassIsPersonalData(superType);
    }

    private boolean superClassIsEntity(TypeElement ele){
        if(ele == null){
            return false;
        }

        Element type = processingEnv.getTypeUtils().asElement(ele.getSuperclass());

        if (type == null || !TypeElement.class.isAssignableFrom(type.getClass())) {
            return false;
        }

        TypeElement superType = (TypeElement) type;
        return hasEntityAnnotation(superType) || superClassIsEntity(superType);
    }
    
    @Override
    public SourceVersion getSupportedSourceVersion() {
    	return SourceVersion.RELEASE_8;
    }
    
    private static boolean hasPersonalDataAnnotation(Element field){
    	return field != null && field.getAnnotation(PersonalData.class) != null;
    }

    private static boolean hasEntityAnnotation(Element field){
        return field != null && field.getAnnotation(Document.class) != null;
    }


    private static boolean isApplicationEntryPoint(Element field) {
        return field != null && field.getAnnotation(RequestMapping.class) != null;
    }

    @Override
    public void finished(TaskEvent task) {
        this.taskEvt = task;
        if (taskEvt.getKind() == TaskEvent.Kind.ANALYZE) {
            if (taskEvt.getSourceFile().getName().endsWith("Test.java")) {
                messager.printMessage(Kind.WARNING, "skip tests");
                return;
            }
            //messager.printMessage(Kind.WARNING, "start: " + taskEvt.getCompilationUnit().getSourceFile().getName());
            Set<String> topLevel = new HashSet<>();
            taskEvt.getCompilationUnit().accept(new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree methodTree, Void aVoid) {
                    final Symbol.MethodSymbol methodEle = (Symbol.MethodSymbol) treeToElement(methodTree);
                    String name = getMethodName(methodEle);
                    if (isApplicationEntryPoint(methodEle)) {
                        topLevel.add(name);
                    }
                    parseDeps(methodEle, methodTree, methodDependencies);
                    return super.visitMethod(methodTree, aVoid);
                }
            }, null);

            String[] parts = task.getCompilationUnit().getSourceFile().getName().split("/");
            String file = parts[parts.length - 1];
            String p = task.getCompilationUnit().getPackageName().toString();

            if (topLevel.isEmpty()) {
                return;
            }
            writeResults(p + "." + file, topLevel);
        }

    }

    private void parseDeps(Symbol.MethodSymbol methodEle, Tree methodTree, Map<String, Set<String>> methodDependencies) {
        String name = getMethodName(methodEle);
        if (!methodDependencies.containsKey(name)) {
            final Set<String> deps = new HashSet<>();
            final Set<String> pd = new HashSet<>();
            methodDependencies.put(name, deps);
            methodTree.accept(new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree inv, Void aVoid) {
                    Symbol.MethodSymbol method = (Symbol.MethodSymbol) treeToElement(inv.getMethodSelect());
                    deps.add(getMethodName(method));
                    return super.visitMethodInvocation(inv, aVoid);
                }

            }, null);
            try {
                methodTree.accept(new PersonalDataScanner(pd), null);
                methodPersonalData.put(name, pd);
            } catch (Exception e) {
                messager.printMessage(Kind.WARNING, "Error: " + e.getStackTrace()[0].getLineNumber());
            }
        }
    }

    private String getMethodName(Symbol.MethodSymbol methodEle) {
        String c = methodEle.owner.getQualifiedName().toString();
        String m = methodEle.getQualifiedName().toString();
        String p = "";
        for (int i = 0; i < methodEle.getParameters().size(); i++) {
            p += methodEle.getParameters().get(i).type;
            if (i < methodEle.getParameters().size() - 1) {
                p += ", ";
            }
        }
        return c + '#' + m + "(" + p + ")";
    }

    private void writeResults(String name, Set<String> entrypoints) {
	    try {
            FileObject fo = processingEnv.getFiler().createResource(StandardLocation.SOURCE_OUTPUT, "pdtree", name + ".txt", null);
            try (OutputStream os = fo.openOutputStream()) {
                try (PrintWriter pw = new PrintWriter(os)) {
                    for (String entrypoint : entrypoints) {
                        Set<String> used = new HashSet<>();
                        pw.println(entrypoint + " - " + getPersonalData(entrypoint));
                        used.add(entrypoint);
                        printTree(entrypoint, pw, 1, used);
                    }
                }
            } catch (Exception e) {
                messager.printMessage(Kind.ERROR, "Failed to write file: " + e.getMessage());
            }
        } catch (Exception e ){

        }
        messager.printMessage(Kind.WARNING, "Printed tree: " + entrypoints.size());
    }

    private void printTree(String key, PrintWriter pw, int depth, Set<String> used) {
	    String padding = "";
        for (int i = 0; i < depth; i++) {
            padding += "    ";
        }
        if (!methodDependencies.containsKey(key)) {
            pw.println(padding + "OutOfScope");
            messager.printMessage(Kind.WARNING, "No deps: " + key);
            return;
        }

        for (String d : methodDependencies.get(key)) {
            pw.println(padding + d + " - " + getPersonalData(d));
            if (!used.contains(d)) {
                used.add(d);
                printTree(d, pw, depth + 1, used);
            }
        }
    }

    private String getPersonalData(String name) {
        String pd = "[";
        if (!methodPersonalData.containsKey(name)) {
            messager.printMessage(Kind.WARNING, "No personal data info: " + name);
        } else {
            for (String a : methodPersonalData.get(name)) {
                pd += a + ", ";
            }
        }
        pd += "]";
        return pd;
    }

    private static Symbol treeToElement(Tree tree){
	    if (tree == null) {
	        return null;
        }
        return TreeInfo.symbolFor((JCTree) tree);
    }

	@Override
	public void started(TaskEvent task) {
	}


    private class PersonalDataScanner extends TreeScanner<Void, Void> {

        private final Set<String> classes;

        public PersonalDataScanner(Set<String> classes) {
            this.classes = classes;
        }


        private void savePersonalData(Symbol ele) {
            classes.add(ele.type.toString());
        }

        private void savePersonalData(TypeMirror type) {
            classes.add(type.toString());
        }

        @Override
        public Void visitParameterizedType(ParameterizedTypeTree parameterizedTypeTree, Void aVoid) {
            for (Tree typeArgument : parameterizedTypeTree.getTypeArguments()) {
                Symbol argEle = treeToElement(typeArgument);
                if (isDatabaseEntity(argEle)) {
                    savePersonalData(argEle);
                }
            }
            return super.visitParameterizedType(parameterizedTypeTree, aVoid);
        }

        @Override
        public Void visitNewArray(NewArrayTree newArrayTree, Void aVoid) {
            Symbol element = treeToElement(newArrayTree.getType());
            if (element == null) {
                return null;
            }
            if (isDatabaseEntity(element)) {
                savePersonalData(element);
            }
            return super.visitNewArray(newArrayTree, aVoid);
        }

        @Override
        public Void visitNewClass(NewClassTree newClassTree, Void aVoid) {
            Symbol element = treeToElement(newClassTree);
            if (element == null) {
                return null;
            }
            Symbol ide = treeToElement(newClassTree.getIdentifier());
            if (isDatabaseEntity(ide)) {
                savePersonalData(ide);
            }
            return super.visitNewClass(newClassTree, aVoid);
        }

        @Override
        public Void visitIdentifier(IdentifierTree identifierTree, Void aVoid) {
            Symbol ele = treeToElement(identifierTree);
            TreePath path = Trees.instance(processingEnv).getPath(ele);
            if (path == null) {
                return null;
            }
            TypeMirror tp = Trees.instance(processingEnv).getTypeMirror(path);
            if (tp != null) {
                switch (tp.getKind()) {
                    case ARRAY: {
                        ArrayType at = (ArrayType) tp;
                        Element array = processingEnv.getTypeUtils().asElement(at.getComponentType());
                        if (isDatabaseEntity(array)) {
                            savePersonalData(at.getComponentType());
                        }
                        break;
                    }
                    case DECLARED: {
                        DeclaredType dt = (DeclaredType) tp;
                        for (TypeMirror mirror : dt.getTypeArguments()) {
                            Element argumentType = processingEnv.getTypeUtils().asElement(mirror);
                            if (isDatabaseEntity(argumentType)) {
                                savePersonalData(argumentType.asType());
                            }
                        }
                    }
                    default:
                        break;
                }
            }
            return super.visitIdentifier(identifierTree, aVoid);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree memberSelectTree, Void aVoid) {
            Element element = treeToElement(memberSelectTree);
            if (element == null) {
                return null;
            }
            Symbol ide = treeToElement(memberSelectTree.getExpression());
            if (isDatabaseEntity(ide)) {
                savePersonalData(ide);
            }
            return super.visitMemberSelect(memberSelectTree, aVoid);
        }
    }
}