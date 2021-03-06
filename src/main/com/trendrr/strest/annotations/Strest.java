/**
 * 
 */
package com.trendrr.strest.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author Dustin Norlander
 * @created Jan 14, 2011
 * 
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Strest {
	String[] route() default "";
	Class[] filters() default {};
	String[] requiredParams() default {};
}
