package fi.geniem.gdpr.personaldataflow;

import java.util.List;
import java.util.Set;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;

@SupportedAnnotationTypes({
	"fi.geniem.gdpr.personaldataflow.PersonalData",
	"fi.geniem.gdpr.personaldataflow.PersonalDataHandler",
    "fi.geniem.gdpr.personaldataflow.PersonalDataEndpoint"
})
public class PersonalDataAnnotationProcessor extends AbstractProcessor implements TaskListener{
		
	private Trees trees;
	private TaskEvent taskEvt;
	private Messager messager;
	
	@Override
	public synchronized void init(ProcessingEnvironment processingEnv) {
		super.init(processingEnv);
		trees = Trees.instance(processingEnv);
		messager = processingEnv.getMessager();
	    JavacTask.instance(processingEnv).setTaskListener(this);
	}
	
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        return false;
    }

    private boolean isPersonalDataElement(Element field){
        return hasPersonalDataAnnotation(field) || isFieldTypePersonalData(field);
    }

    private boolean isPersonalData(Symbol field){
        return hasPersonalDataAnnotation(field) || isFieldTypePersonalData(field) || hasPersonalDataTypeParameter(field);
    }

    private boolean hasPersonalDataTypeParameter(Symbol field) {
        //messager.printMessage(Kind.WARNING, "testing " + field.getTypeParameters().size() + field.getQualifiedName(), field);
	    return false;
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
    
    @Override
    public SourceVersion getSupportedSourceVersion() {
    	return SourceVersion.RELEASE_8;
    }
    
    private static boolean hasPersonalDataAnnotation(Element field){
    	return field != null && field.getAnnotation(PersonalData.class) != null;
    }

    @Override 
    public void finished(TaskEvent task) {
        this.taskEvt = task;
        if (taskEvt.getKind() == TaskEvent.Kind.ANALYZE) {
            taskEvt.getCompilationUnit().accept(new TreeScanner<Void, Void>() {

                @Override
                public Void visitClass(ClassTree classTree, Void aVoid) {
                    final Symbol classEle = treeToElement(classTree);
                    classTree.accept(new TreeScanner<Void, Void>(){
                        @Override
                        public Void visitVariable(VariableTree variable, Void v) {
                            Symbol element = treeToElement(variable);
                            if(element == null || !element.getModifiers().contains(Modifier.STATIC)){
                                return null;
                            }
                            variable.accept(new TreeScanner<Void, Void>() {
                                @Override
                                public Void visitParameterizedType(ParameterizedTypeTree parameterizedTypeTree, Void aVoid) {
                                    for (Tree typeArgument : parameterizedTypeTree.getTypeArguments()) {
                                        Symbol argEle = treeToElement(typeArgument);
                                        if (isPersonalData(argEle) && !isSafeContainer(classEle)) {
                                            warn("Unsafe @PersonalData: " + argEle, typeArgument);
                                        }
                                    }
                                    return super.visitParameterizedType(parameterizedTypeTree, aVoid);
                                }
                            }, null);
                            if(isPersonalData(element) && !isSafeContainer(classEle)){
                                warn("Unsafe @PersonalData: " + element, variable);
                            }
                            return super.visitVariable(variable, v);
                        }
                    }, null);
                    return super.visitClass(classTree, aVoid);
                }

                @Override
                public Void visitMethod(MethodTree methodTree, Void aVoid) {
                    final Symbol methodEle = treeToElement(methodTree);
                    methodTree.accept(new TreeScanner<Void, Void>(){

                        @Override
                        public Void visitMethodInvocation(MethodInvocationTree inv, Void aVoid) {
                            Symbol method = treeToElement(inv.getMethodSelect());

                            for(ExpressionTree argument : inv.getArguments()){
                                Symbol argumentEle = treeToElement(argument);

                                argument.accept(new TreeScanner<Void, Void>(){

                                    @Override
                                    public Void visitParameterizedType(ParameterizedTypeTree parameterizedTypeTree, Void aVoid) {
                                        for(Tree typeArgument : parameterizedTypeTree.getTypeArguments()){
                                            Symbol argEle = treeToElement(typeArgument);
                                            if(isPersonalData(argEle) && !isSafeContainer(method) && !isEndpoint(method)) {
                                                warn("Unsafe @PersonalData: " + argEle, typeArgument);
                                            }
                                        }
                                        return super.visitParameterizedType(parameterizedTypeTree, aVoid);
                                    }

                                    @Override
                                    public Void visitNewArray(NewArrayTree newArrayTree, Void aVoid) {
                                        Symbol element = treeToElement(newArrayTree.getType());
                                        if(element == null){
                                            return null;
                                        }
                                        if(isPersonalData(element) && !isSafeContainer(method) && !isEndpoint(method)){
                                            warn("Unsafe @PersonalData: " +element, newArrayTree);
                                        }
                                        return super.visitNewArray(newArrayTree, aVoid);
                                    }

                                    @Override
                                    public Void visitNewClass(NewClassTree newClassTree, Void aVoid) {
                                        Symbol element = treeToElement(newClassTree);
                                        if(element == null){
                                            return null;
                                        }
                                        Symbol ide = treeToElement(newClassTree.getIdentifier());
                                        if(isPersonalData(ide) && !isSafeContainer(method) && !isEndpoint(method)){
                                            warn("Unsafe @PersonalData: " + ide, newClassTree);
                                        }
                                        return super.visitNewClass(newClassTree, aVoid);
                                    }

                                    @Override
                                    public Void visitIdentifier(IdentifierTree identifierTree, Void aVoid) {
                                        Symbol ele = treeToElement(identifierTree);
                                        TreePath path = Trees.instance(processingEnv).getPath(ele);
                                        if(path == null){
                                            return null;
                                        }
                                        TypeMirror tp = Trees.instance(processingEnv).getTypeMirror(path);
                                        if (tp != null) {
                                            switch (tp.getKind()) {
                                                case ARRAY: {
                                                    ArrayType at = (ArrayType) tp;
                                                    Element array = processingEnv.getTypeUtils().asElement(at.getComponentType());
                                                    if (isPersonalDataElement(array) && !isSafeContainer(method) && !isEndpoint(method)) {
                                                        warn("Unsafe @PersonalData: " + argumentEle, identifierTree);
                                                    }
                                                    break;
                                                }
                                                case DECLARED: {
                                                    DeclaredType dt = (DeclaredType) tp;
                                                    for (TypeMirror mirror : dt.getTypeArguments()) {
                                                        Element argumentType = processingEnv.getTypeUtils().asElement(mirror);
                                                        if (isPersonalDataElement(argumentType) && !isSafeContainer(method) && !isEndpoint(method)) {
                                                            warn("Unsafe @PersonalData: " + argumentEle, identifierTree);
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
                                        if(element == null){
                                            return null;
                                        }
                                        Symbol ide = treeToElement(memberSelectTree.getExpression());
                                        if(isPersonalData(ide)
                                                && !isSafeContainer(method)
                                                && !isEndpoint(method)
                                                && !isSafeContainer(methodEle)){
                                            warn("Unsafe @PersonalData: " + ide, memberSelectTree);
                                        }
                                        return super.visitMemberSelect(memberSelectTree, aVoid);
                                    }
                                }, null);
                                if(argumentEle == null){
                                    continue;
                                }

                                if(isPersonalData(argumentEle) && !isSafeContainer(method) && !isEndpoint(method)){
                                    warn("Unsafe @PersonalData: " + argumentEle, argument);
                                }
                            }
                            return super.visitMethodInvocation(inv, aVoid);
                        }

                        @Override
                        public Void visitParameterizedType(ParameterizedTypeTree parameterizedTypeTree, Void aVoid) {
                            for(Tree arg : parameterizedTypeTree.getTypeArguments()){
                                Symbol argEle = treeToElement(arg);
                                if(isPersonalData(argEle) && !isSafeContainer(methodEle)) {
                                    warn("Unsafe @PersonalData: " + argEle, arg);
                                }
                            }
                            return super.visitParameterizedType(parameterizedTypeTree, aVoid);
                        }

                        @Override
                        public Void visitNewArray(NewArrayTree newArrayTree, Void aVoid) {
                            Symbol element = treeToElement(newArrayTree.getType());
                            if(element == null){
                                return null;
                            }
                            if(isPersonalData(element) && !isSafeContainer(methodEle)){
                                warn("Unsafe @PersonalData: " + element, newArrayTree);
                            }
                            return super.visitNewArray(newArrayTree, aVoid);
                        }

                        @Override
                        public Void visitTypeCast(TypeCastTree typeCastTree, Void aVoid) {
                            Symbol element = treeToElement(typeCastTree.getType());
                            if(element == null){
                                return null;
                            }
                            if(isPersonalData(element) && !isSafeContainer(methodEle)){
                                warn("Unsafe @PersonalData: " + element, typeCastTree);
                            }
                            return super.visitTypeCast(typeCastTree, aVoid);
                        }

                        @Override
                        public Void visitNewClass(NewClassTree newClassTree, Void aVoid) {
                            Symbol element = treeToElement(newClassTree);
                            if(element == null){
                                return null;
                            }
                            Symbol ide = treeToElement(newClassTree.getIdentifier());
                            if(isPersonalData(ide) && !isSafeContainer(methodEle)){
                                warn("Unsafe @PersonalData: " + ide, newClassTree);
                            }
                            return super.visitNewClass(newClassTree, aVoid);
                        }

                        @Override
                        public Void visitVariable(VariableTree variable, Void v) {
                            Symbol element = treeToElement(variable.getType());
                            variable.accept(new TreeScanner<Void, Void>(){
                                @Override
                                public Void visitArrayType(ArrayTypeTree arrayTypeTree, Void aVoid) {
                                    Symbol element = treeToElement(arrayTypeTree.getType());
                                    if(isPersonalData(element) && !isSafeContainer(methodEle)){
                                        warn("Unsafe @PersonalData: " + element, arrayTypeTree);
                                    }
                                    return super.visitArrayType(arrayTypeTree, aVoid);
                                }
                            }, null);
                            if(element == null){
                                return null;
                            }
                            if(isPersonalData(element) && !isSafeContainer(methodEle)){
                                warn("Unsafe @PersonalData: " + variable, variable);
                            }
                            return super.visitVariable(variable, v);
                        }

                        @Override
                        public Void visitTypeParameter(TypeParameterTree typeParameterTree, Void aVoid) {
                            Element element = treeToElement(typeParameterTree);
                            if(element == null){
                                return null;
                            }
                            for (Tree b : typeParameterTree.getBounds()) {
                                Symbol ide = treeToElement(b);
                                if(isPersonalData(ide) && !isSafeContainer(methodEle)){
                                    warn("Unsafe @PersonalData: " + ide, typeParameterTree);
                                }
                            }
                            Symbol ide = treeToElement(typeParameterTree);
                            if(isPersonalData(ide) && !isSafeContainer(methodEle)){
                                warn("Unsafe @PersonalData: " + ide, typeParameterTree);
                            }
                            return super.visitTypeParameter(typeParameterTree, aVoid);
                        }

                    }, null);
                    return super.visitMethod(methodTree, aVoid);
                }
            }, null);
        }
    }

    private static Symbol treeToElement(Tree tree){
	    if (tree == null) {
	        return null;
        }
        return TreeInfo.symbolFor((JCTree) tree);
    }

    private void warn(String text, Tree argument){
        trees.printMessage(Kind.WARNING,text, argument, taskEvt.getCompilationUnit());
    }
    
    private boolean isSafeContainer(Symbol element){
        if(element == null){
            return false;
        }
    	return isPersonalDataHandler(element) 
    			|| isPersonalData(element)
    			|| isParentSafeContainer(element);
    }
    
    private boolean isParentSafeContainer(Symbol element){
    	return element.getEnclosingElement() != null && isSafeContainer(element.getEnclosingElement());
    }

    private static boolean isPersonalDataHandler(Element field){
        return field != null && field.getAnnotation(PersonalDataHandler.class) != null;
    }

    private static boolean isEndpoint(Element field){
        return field != null && field.getAnnotation(PersonalDataEndpoint.class) != null;
    }

	@Override
	public void started(TaskEvent arg0) {
	}
}