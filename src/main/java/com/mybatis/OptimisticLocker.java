package main.java.com.mybatis;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @Author jiangyunxiong
 * @Date 2019/7/27 1:31 AM
 */
// 适用方法, 注解不仅被保存到class文件中，jvm加载class文件之后，仍然存在；
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface OptimisticLocker {

    boolean value() default true;

}
