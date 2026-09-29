/*
 * Reconstructed for the GSL5 CLI fork: decompiled (CFR 0.152) from the upstream
 * Release jar (origin-jar/gsl5.jar) because src/ shipped without these annotations.
 */
package core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(value = RetentionPolicy.RUNTIME)
@Target(value = {ElementType.METHOD})
public @interface I18NAction {
    public Class<?> targetClass();
}
