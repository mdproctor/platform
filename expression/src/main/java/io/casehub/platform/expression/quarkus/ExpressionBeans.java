package io.casehub.platform.expression.quarkus;

import io.casehub.platform.api.expression.ExpressionEngine;
import io.casehub.platform.expression.DefaultExpressionEngineRegistry;
import io.casehub.platform.expression.JQExpressionEngine;
import io.casehub.platform.expression.JexlExpressionEngine;
import io.casehub.platform.expression.MvelExpressionEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

import java.util.List;

@ApplicationScoped
public class ExpressionBeans {

    @Produces
    @ApplicationScoped
    public MvelExpressionEngine mvelExpressionEngine() {
        return new MvelExpressionEngine();
    }

    @Produces
    @ApplicationScoped
    public JQExpressionEngine jqExpressionEngine() {
        return new JQExpressionEngine();
    }

    @Produces
    @ApplicationScoped
    public JexlExpressionEngine jexlExpressionEngine() {
        return new JexlExpressionEngine();
    }

    @Produces
    @ApplicationScoped
    public DefaultExpressionEngineRegistry defaultExpressionEngineRegistry(
            Instance<ExpressionEngine> engines) {
        return new DefaultExpressionEngineRegistry(engines.stream().toList());
    }
}
