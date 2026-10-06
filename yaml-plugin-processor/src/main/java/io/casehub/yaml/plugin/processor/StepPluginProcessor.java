package io.casehub.yaml.plugin.processor;

import io.casehub.yaml.plugin.api.Execute;
import io.casehub.yaml.plugin.api.Plugin;
import io.casehub.yaml.plugin.api.Result;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SupportedAnnotationTypes("*")
public class StepPluginProcessor extends AbstractProcessor {

    private boolean processed;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (processed || roundEnv.processingOver()) return false;
        processed = true;

        for (Element element : roundEnv.getElementsAnnotatedWith(Plugin.class)) {
            if (element.getKind() != ElementKind.RECORD) {
                error(element, "@Plugin must be applied to a record");
                continue;
            }
            TypeElement typeElement = (TypeElement) element;
            Plugin      annotation  = typeElement.getAnnotation(Plugin.class);
            PluginModel model       = validate(typeElement, annotation);
            if (model != null) {
                generate(model);
            }
        }
        return false;
    }

    private PluginModel validate(TypeElement typeElement, Plugin annotation) {
        List<ExecutableElement> executeMethods = new ArrayList<>();
        for (Element enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getAnnotation(Execute.class) != null) {
                executeMethods.add((ExecutableElement) enclosed);
            }
        }

        if (executeMethods.isEmpty()) {
            error(typeElement, "@Plugin class must have exactly one @Execute method");
            return null;
        }
        if (executeMethods.size() > 1) {
            error(typeElement, "@Plugin class must have exactly one @Execute method, found " + executeMethods.size());
            return null;
        }

        ExecutableElement executeMethod = executeMethods.get(0);
        TypeMirror returnType = executeMethod.getReturnType();
        if (!returnType.toString().equals(Result.class.getCanonicalName())) {
            error(executeMethod, "@Execute method must return Result, found " + returnType);
            return null;
        }

        List<RecordComponentElement> fields = new ArrayList<>(typeElement.getRecordComponents());
        List<PluginModel.ServiceParam> serviceParams = new ArrayList<>();
        for (var param : executeMethod.getParameters()) {
            serviceParams.add(new PluginModel.ServiceParam(
                param.asType().toString(),
                param.asType().toString()));
        }

        return new PluginModel(annotation.value(), annotation.description(),
            annotation.portability().name(), annotation.capability(),
            typeElement, fields, executeMethod, serviceParams);
    }

    private void generate(PluginModel model) {
        try {
            new SchemaEmitter().emit(model, processingEnv.getFiler());
            new BinderEmitter().emit(model, processingEnv.getFiler());
            new RegistryEmitter().emit(model, processingEnv.getFiler());
        } catch (Exception e) {
            error(model.pluginClass(),
                "Code generation failed for @Plugin '" + model.name() + "': " + e.getMessage());
        }
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
