package io.quarkus.hibernate.validator.runtime.locale;

import java.util.Locale;

import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.hibernate.validator.spi.messageinterpolation.LocaleResolver;
import org.hibernate.validator.spi.messageinterpolation.LocaleResolverContext;

/**
 * Wrapper for potentially multiple locale resolvers. The first one that actually returns a non-null Locale will be used.
 */
@Singleton
@Named("locale-resolver-wrapper")
public class LocaleResolversWrapper implements LocaleResolver {

    @Inject
    Instance<AbstractLocaleResolver> resolvers;

    @Override
    public Locale resolve(LocaleResolverContext context) {
        for (AbstractLocaleResolver resolver : resolvers) {
            Locale locale = resolver.resolve(context);
            if (locale != null) {
                return locale;
            }
        }
        return context.getDefaultLocale();
    }

}
