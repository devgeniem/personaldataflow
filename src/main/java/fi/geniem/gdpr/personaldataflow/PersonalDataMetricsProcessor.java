package fi.geniem.gdpr.personaldataflow;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;
import javafx.util.Pair;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@SupportedAnnotationTypes({"*",})
public class PersonalDataMetricsProcessor extends AbstractProcessor implements TaskListener {

    private Trees trees;
    private TaskEvent taskEvt;
    private Messager messager;

    private Map<String, Set<String>> methodDependencies;
    private Map<String, List<Set<String>>> interfaceImplementations;

    private Map<String, Set<String>> methodPersonalData;
    private Map<String, Set<Transfer>> methodDataRecipients;
    private Set<WaitList> waitLists;


    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        trees = Trees.instance(processingEnv);
        messager = processingEnv.getMessager();
        JavacTask.instance(processingEnv).setTaskListener(this);
        methodDependencies = new HashMap<>();
        methodPersonalData = new HashMap<>();
        methodDataRecipients = new HashMap<>();
        interfaceImplementations = new HashMap<>();
        waitLists = new HashSet<>();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        return false;
    }

    private boolean isPersonalData(Element field) {
        return hasPersonalDataAnnotation(field) || isFieldTypePersonalData(field);
    }

    private boolean isDatabaseEntity(Element field) {
        return hasEntityAnnotation(field) || isFieldTypeEntity(field);
    }

    private boolean isFieldTypePersonalData(Element field) {
        if (field == null) {
            return false;
        }

        Element type = processingEnv.getTypeUtils().asElement(field.asType());

        if (type == null || !TypeElement.class.isAssignableFrom(type.getClass())) {
            return false;
        }

        TypeElement fieldTypeElement = (TypeElement) type;
        return hasPersonalDataAnnotation(fieldTypeElement) || superClassIsPersonalData(fieldTypeElement);
    }

    private boolean isFieldTypeEntity(Element field) {
        if (field == null) {
            return false;
        }

        Element type = processingEnv.getTypeUtils().asElement(field.asType());

        if (type == null || !TypeElement.class.isAssignableFrom(type.getClass())) {
            return false;
        }

        TypeElement fieldTypeElement = (TypeElement) type;
        return hasEntityAnnotation(fieldTypeElement) || superClassIsEntity(fieldTypeElement);
    }

    private boolean superClassIsPersonalData(TypeElement ele) {
        if (ele == null) {
            return false;
        }

        Element type = processingEnv.getTypeUtils().asElement(ele.getSuperclass());

        if (type == null || !TypeElement.class.isAssignableFrom(type.getClass())) {
            return false;
        }

        TypeElement superType = (TypeElement) type;
        return hasPersonalDataAnnotation(superType) || superClassIsPersonalData(superType);
    }

    private boolean superClassIsEntity(TypeElement ele) {
        if (ele == null) {
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

    private static boolean hasPersonalDataAnnotation(Element field) {
        return field != null && field.getAnnotation(PersonalData.class) != null;
    }

    private static boolean hasEntityAnnotation(Element field) {
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
                return;
            }

            Set<String> topLevel = new HashSet<>();
            Set<String> currentRoundResolved = new HashSet<>();
            Set<Type> interfaces = new HashSet<>();
            taskEvt.getCompilationUnit().accept(new TreeScanner<Void, Void>() {

                @Override
                public Void visitMethod(MethodTree methodTree, Void aVoid) {
                    final Symbol.MethodSymbol methodEle = (Symbol.MethodSymbol) treeToElement(methodTree);

                    Symbol.ClassSymbol owner = ((Symbol.ClassSymbol) methodEle.owner);
                    interfaces.addAll(owner.getInterfaces());

                    String name = getMethodName(methodEle);
                    if (isApplicationEntryPoint(methodEle)) {
                        topLevel.add(name);
                    }
                    parseDeps(methodEle, methodTree, currentRoundResolved, interfaces);
                    return super.visitMethod(methodTree, aVoid);
                }
            }, null);

            String origFile = task.getCompilationUnit().getSourceFile().getName().replace('\\', '/');
            String[] parts = origFile.split("/");
            String file = parts[parts.length - 1];
            file = file.replaceFirst(".java", "");
            String p = task.getCompilationUnit().getPackageName().toString();
            String name =  p + "." + file;

            if (!topLevel.isEmpty()) {
                handleResults(name, topLevel, false);
            }

            Set<WaitList> toResolve = new HashSet<>();
            Set<WaitList> toRemove = new HashSet<>();

            for (String s : currentRoundResolved) {
                for (WaitList wl : waitLists) {
                    if (wl.waitingFor.contains(s)) {
                        wl.waitingFor.remove(s);
                        toResolve.add(wl);
                        if (wl.waitingFor.isEmpty()) {
                            toRemove.add(wl);
                        }
                    }
                }
            }

            for (WaitList wl : toRemove) {
                waitLists.remove(wl);
            }

            for (WaitList wl : toResolve) {
                handleResults(wl.name, wl.entryPoints, true);
            }

        }
    }

    private void parseDeps(Symbol.MethodSymbol methodEle, Tree methodTree, Set<String> currentRound, Set<Type> interfaces) {
        String name = getMethodName(methodEle);
        if (!methodDependencies.containsKey(name) && !methodEle.getModifiers().contains(Modifier.ABSTRACT)) {
            final Set<String> deps = new HashSet<>();
            final Set<Transfer> dr = new HashSet<>();
            final Set<String> pd = new HashSet<>();
            methodDependencies.put(name, deps);
            if (!interfaces.isEmpty()) {
                for (Type t : interfaces) {
                    String iname = getMethodName(methodEle, t);
                    if (!interfaceImplementations.containsKey(iname)) {
                        interfaceImplementations.put(iname, new ArrayList<>());
                    }
                    interfaceImplementations.get(iname).add(deps);
                    currentRound.add(iname);
                }
            }

            methodTree.accept(new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethodInvocation(MethodInvocationTree inv, Void aVoid) {
                    Symbol.MethodSymbol method = (Symbol.MethodSymbol) treeToElement(inv.getMethodSelect());
                    deps.add(getMethodName(method));
                    return super.visitMethodInvocation(inv, aVoid);
                }
            }, null);
            try {
                methodTree.accept(new PersonalDataScanner(pd, dr), null);
                methodPersonalData.put(name, pd);
                methodDataRecipients.put(name, dr);
                if (!interfaces.isEmpty()) {
                    for (Type t : interfaces) {
                        String iname = getMethodName(methodEle, t);
                        if (!methodPersonalData.containsKey(iname)) {
                            methodPersonalData.put(iname, new HashSet<>());
                        }
                        methodPersonalData.get(iname).addAll(pd);

                        if (!methodDataRecipients.containsKey(iname)) {
                            methodDataRecipients.put(iname, new HashSet<>());
                        }
                        methodDataRecipients.get(iname).addAll(dr);
                    }
                }
            } catch (Exception e) {
                messager.printMessage(Kind.WARNING, "Error: " + e.getStackTrace()[0].getLineNumber());
            }
            currentRound.add(name);
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

    private String getMethodName(Symbol.MethodSymbol methodEle, Type in) {
        String c = in.tsym.getQualifiedName().toString();
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

    private void handleResults(String name, Set<String> entrypoints, boolean retry) {
        Set<String> waitingFor = new HashSet<>();

        Set<String> controllerPersonalData = new HashSet<>();
        Set<Transfer> controllerDataRecipients = new HashSet<>();
        Path path = Paths.get("/home/pdtree/", name + ".json");
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            try (PrintWriter pw = new PrintWriter(writer)) {
                pw.println("{");
                pw.println("    \"name\": \"" + name + "\",");
                pw.println("    \"optOut\": false,");
                pw.println("    \"required\": true,");
                pw.println("    \"retention\": null,");
                pw.println("    \"pm\": null,");
                pw.println("    \"description\": \"\",");
                pw.println("    \"purposes\": [");
                int i = 0;
                for (String entrypoint : entrypoints) {
                    Set<String> used = new HashSet<>();
                    Set<Transfer> recipients = new HashSet<>();
                    Set<String> personalData = new HashSet<>();
                    personalData.addAll(getMethodPersonalData(entrypoint));
                    recipients.addAll(getMethodDataRecipients(entrypoint));
                    used.add(entrypoint);
                    Pair<Set<String>, Set<Transfer>> tree = readTree(entrypoint, used, waitingFor);
                    personalData.addAll(tree.getKey());
                    recipients.addAll(tree.getValue());

                    pw.println("        {");
                    pw.println("            \"name\": \"" + entrypoint + "\",");
                    pw.println("            \"optOut\": false,");
                    pw.println("            \"required\": true,");
                    pw.println("            \"retention\": null,");
                    pw.println("            \"description\": \"\",");
                    pw.println("            \"pm\": null,");
                    pw.println("            \"purposes\": [],");
                    pw.println("            \"data\": " + formatArrayToJson(personalData) + ",");
                    pw.println("            \"transfers\": " + formatTransferToJson(recipients));
                    pw.println("        " + ((i == entrypoints.size() - 1) ? "}" : "},"));
                    controllerPersonalData.addAll(personalData);
                    controllerDataRecipients.addAll(recipients);
                    i++;
                }
                pw.println("    ],");
                pw.println("    \"data\": " + formatArrayToJson(controllerPersonalData) + ",");
                pw.println("    \"transfers\": " + formatTransferToJson(controllerDataRecipients));
                pw.println("}");
            }
        } catch (IOException e) {
            messager.printMessage(Kind.ERROR, "Failed to write file: " + e.toString());
        }
        if (!waitingFor.isEmpty()) {
            WaitList list = null;
            for (WaitList wl : waitLists) {
                if (wl.name.equals(name)) {
                    list = wl;
                }
            }
            if (list != null) {
                list.waitingFor = waitingFor;
            } else {
                waitLists.add(new WaitList(name, entrypoints, waitingFor));
            }
        }
    }

    private Pair<Set<String>, Set<Transfer>> readTree(String key, Set<String> used, Set<String> waitingFor) {
        Set<String> personalData = new HashSet<>();
        Set<Transfer> dataRecipients = new HashSet<>();

        if (interfaceImplementations.containsKey(key) && !interfaceImplementations.get(key).isEmpty()) {
            if (interfaceImplementations.get(key).size() == 1) {
                Set<String> impl = interfaceImplementations.get(key).get(0);
                for (String d : impl) {
                    personalData.addAll(getMethodPersonalData(d));
                    dataRecipients.addAll(getMethodDataRecipients(d));
                    if (!used.contains(d)) {
                        used.add(d);
                        Pair<Set<String>, Set<Transfer>> tree = readTree(d, used, waitingFor);
                        personalData.addAll(tree.getKey());
                        dataRecipients.addAll(tree.getValue());
                    }
                }
            } else {
                for (Set<String> impl : interfaceImplementations.get(key)) {
                    for (String d : impl) {
                        personalData.addAll(getMethodPersonalData(d));
                        dataRecipients.addAll(getMethodDataRecipients(d));
                        if (!used.contains(d)) {
                            used.add(d);
                            Pair<Set<String>, Set<Transfer>> tree = readTree(d, used, waitingFor);
                            personalData.addAll(tree.getKey());
                            dataRecipients.addAll(tree.getValue());
                        }
                    }
                }
            }
        } else if (methodDependencies.containsKey(key)) {
            for (String d : methodDependencies.get(key)) {
                personalData.addAll(getMethodPersonalData(d));
                dataRecipients.addAll(getMethodDataRecipients(d));
                if (!used.contains(d)) {
                    used.add(d);
                    Pair<Set<String>, Set<Transfer>> tree = readTree(d, used, waitingFor);
                    personalData.addAll(tree.getKey());
                    dataRecipients.addAll(tree.getValue());
                }
            }
        }  else {
            String[] auw = taskEvt.getCompilationUnit().getPackageName().toString().split("\\.");
            String underWork = auw.length > 4 ? String.join(".", auw[0], auw[1], auw[2], auw[3]) : "";
            String[] ma = key.split("\\.");
            String missing = ma.length > 4 ? String.join(".", ma[0], ma[1], ma[2], ma[3]) : "";
            if (underWork.equals(missing)) {
                waitingFor.add(key);
            }
        }
        return new Pair<>(personalData, dataRecipients);
    }

    private String formatArrayToJson(Set<String> data) {
        String pd = "";
        Iterator<String> it = data.iterator();
        while(it.hasNext()) {
            pd += ("\"" + it.next() + "\"");
            if (it.hasNext()) {
                pd += ", ";
            }
        }
        return "[" + pd + "]";
    }

    private String formatTransferToJson(Set<Transfer> data) {
        String pd = "";
        Iterator<Transfer> it = data.iterator();
        while(it.hasNext()) {
            Transfer t = it.next();
            pd += ("{\"recipientId\": \"" + t.recipientId + "\", \"policyURL\": \"" + t.policyURL + "\"}");
            if (it.hasNext()) {
                pd += ", ";
            }
        }
        return "[" + pd + "]";
    }

    private Set<String> getMethodPersonalData(String name) {
        if (methodPersonalData.containsKey(name)) {
            return methodPersonalData.get(name);
        }
        return new HashSet<>();
    }

    private Set<Transfer> getMethodDataRecipients(String name) {
        if (methodDataRecipients.containsKey(name)) {
            return methodDataRecipients.get(name);
        }
        return new HashSet<>();
    }

    private static Symbol treeToElement(Tree tree) {
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
        private final Set<Transfer> recipients;

        public PersonalDataScanner(Set<String> classes, Set<Transfer> recipients) {
            this.classes = classes;
            this.recipients = recipients;
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
                if (isDatabaseEntity(argEle) && isPersonalData(argEle)) {
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
            if (isDatabaseEntity(element) && isPersonalData(element)) {
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
            if (isDatabaseEntity(ide) && isPersonalData(ide)) {
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
                        if (isDatabaseEntity(array) && isPersonalData(array)) {
                            savePersonalData(at.getComponentType());
                        }
                        break;
                    }
                    case DECLARED: {
                        DeclaredType dt = (DeclaredType) tp;
                        for (TypeMirror mirror : dt.getTypeArguments()) {
                            Element argumentType = processingEnv.getTypeUtils().asElement(mirror);
                            if (isDatabaseEntity(argumentType) && isPersonalData(argumentType)) {
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
            if (isDatabaseEntity(ide) && isPersonalData(ide)) {
                savePersonalData(ide);
            }
            return super.visitMemberSelect(memberSelectTree, aVoid);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree inv, Void aVoid) {
            Symbol.MethodSymbol method = (Symbol.MethodSymbol) treeToElement(inv.getMethodSelect());
            if (isTransfer(method)) {
                recipients.add(new Transfer(method.getAnnotation(PersonalDataTransfer.class)));
            }
            return super.visitMethodInvocation(inv, aVoid);
        }
    }

    private class WaitList {

        public Set<String> waitingFor;

        public Set<String> entryPoints;

        public final String name;

        public WaitList(String name, Set<String> entryPoints, Set<String> waitingFor) {
            this.name = name;
            this.entryPoints = entryPoints;
            this.waitingFor = waitingFor;
        }
    }

    private static boolean isTransfer(Element field){
        return field != null && field.getAnnotation(PersonalDataTransfer.class) != null;
    }

    private class Transfer {

        public final String policyURL;
        public final String recipientId;

        public Transfer(PersonalDataTransfer td) {
            this.policyURL = td.policyURL();
            this.recipientId = td.dataRecipientId();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Transfer)) {
                return false;
            }
            Transfer o = (Transfer)obj;
            return recipientId.equals(o.recipientId) && policyURL.equals(o.policyURL);
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 31 * result + recipientId.hashCode();
            result = 31 * result + policyURL.hashCode();
            return result;
        }
    }
}