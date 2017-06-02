package fi.geniem.gdpr.personaldataflow;

import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;

@SupportedAnnotationTypes({
	"fi.geniem.gdpr.personaldataflow.PersonalData",
	"fi.geniem.gdpr.personaldataflow.PersonalDataHandler"})
public class PersonalDataAnnotationProcessor extends AbstractProcessor implements TaskListener{
		
	private Trees trees;
	
	@Override
	public synchronized void init(ProcessingEnvironment processingEnv) {
		super.init(processingEnv);
		trees = Trees.instance(processingEnv);
	    JavacTask.instance(processingEnv).setTaskListener(this);
	}
	
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        return true;
    }
   
    private boolean isPersonalData(Element field){
    	return hasPersonalDataAnnotation(field) || isFieldTypePersonalData(field);
    }
    
    private boolean isFieldTypePersonalData(Element field){
    	if(field == null){
    		return false;
    	}
    	TypeElement fieldTypeElement = (TypeElement) processingEnv.
                getTypeUtils().asElement(field.asType());
    	return hasPersonalDataAnnotation(fieldTypeElement);
    }
    
    @Override
    public SourceVersion getSupportedSourceVersion() {
    	return SourceVersion.RELEASE_8;
    }
    
    private static boolean hasPersonalDataAnnotation(Element field){
    	return field != null && field.getAnnotation(PersonalData.class) != null;
    }
    
    private static boolean isPersonalDataHandler(Element field){
    	return field != null && field.getAnnotation(PersonalDataHandler.class) != null;
    }

    @Override 
    public void finished(TaskEvent taskEvt) {
        if (taskEvt.getKind() == TaskEvent.Kind.ANALYZE) {
            taskEvt.getCompilationUnit().accept(new TreeScanner<Void, Void>() {
            	
            	@Override
            	public Void visitVariable(VariableTree variable, Void v) {
            		Element element = TreeInfo.symbolFor((JCTree) variable);
            		Element parent = element.getEnclosingElement();
            		if(isPersonalData(element) && !isSafeContainer(parent)){
	                    trees.printMessage(Kind.WARNING, 
	                    		"Unsafe personal data: " + element.getSimpleName(), 
	                    		(JCTree) variable, taskEvt.getCompilationUnit());
            		}
            		return super.visitVariable(variable, v);
            	}
            	
            	@Override
            	public Void visitMethodInvocation(MethodInvocationTree inv, Void v) {
            		for(ExpressionTree argument : inv.getArguments()){
            			Element element = TreeInfo.symbolFor((JCTree) argument);
                        Element method = TreeInfo.symbol((JCTree) inv.getMethodSelect());
            			if(isPersonalData(element) && !isSafeContainer(method) && !isEndpoint(method)){
	                        trees.printMessage(Kind.WARNING, 
	                        		"invocation: " + element, 
	                        		(JCTree) argument, taskEvt.getCompilationUnit());
            			}
            		}
            		return super.visitMethodInvocation(inv, v);
            	}
            	
            }, null);
        }
    }
    
    private static boolean isEndpoint(Element field){
    	return field != null && field.getAnnotation(PersonalDataEndpoint.class) != null;
    }
    
    private boolean isSafeContainer(Element element){
    	return isPersonalDataHandler(element) 
    			|| isPersonalData(element)
    			|| isParentSafeContainer(element);
    }
    
    private boolean isParentSafeContainer(Element element){
    	return element.getEnclosingElement() != null && isSafeContainer(element.getEnclosingElement());
    }

	@Override
	public void started(TaskEvent arg0) {
	}
}