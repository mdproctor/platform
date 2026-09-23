package io.casehub.platform.api.expression;

public class InvocationDeniedException extends RuntimeException {

    private final String beanClassName;
    private final String methodName;

    public InvocationDeniedException(String beanClassName, String methodName) {
        super("Invocation denied: " + beanClassName + "::" + methodName);
        this.beanClassName = beanClassName;
        this.methodName = methodName;
    }

    public String beanClassName() { return beanClassName; }

    public String methodName() { return methodName; }
}
