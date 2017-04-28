package fi.geniem.gdpr.personaldataflow;

import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic.Kind;

@SupportedAnnotationTypes({"fi.geniem.gdpr.personaldataflow.PersonalData"})
public class PersonalDataAnnotationProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!roundEnv.processingOver()) {
        	demoWarning(roundEnv);
        }
        return true;
    }
    
    private void demoWarning(RoundEnvironment roundEnv){
    	Set< ? extends Element> elts = roundEnv.getRootElements();
    	Set< ? extends Element> types = ElementFilter.typesIn(elts);
        for (Element type : types) {
        	boolean isSafe = isElementSafe(type);
        	if(!isSafe){
        		findAllPersonalDatas(type);
        	}
        }
    }
    
    private void findAllPersonalDatas(Element type){
    	List<? extends Element> elements = type.getEnclosedElements();
    	List<? extends Element> fields = 
    				ElementFilter.fieldsIn(elements);
    		for(Element field : fields){
    			if(field.getAnnotation(PersonalData.class) != null
    					|| field.asType().getClass().getAnnotation(PersonalData.class) != null){
    				processingEnv.getMessager().printMessage(
    	            		Kind.WARNING,
    	                    String.format("Unsafe personal data: %s", field),
    	                    type);
    			}
    		}
    }
    
    private static boolean isElementSafe(Element type){
    	return type.getAnnotation(PersonalData.class) != null || type.getAnnotation(PersonalDataHandler.class) != null;
    }
    
    @Override
    public SourceVersion getSupportedSourceVersion() {
    	return SourceVersion.RELEASE_8;
    }

}