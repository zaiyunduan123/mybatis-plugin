package main.java.com.mybatis;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.lang.reflect.Proxy;

/**
 * @Author jiangyunxiong
 * @Date 2019/7/27 2:20 AM
 */
public final class PluginUtil {

    private PluginUtil(){}

    /**
     * 根据代理对象 递归获取原始目标对象
     * @param target
     * @return
     */
    public static Object processTarget(Object target){
        if (Proxy.isProxyClass(target.getClass())){
            MetaObject metaObject = SystemMetaObject.forObject(target);
            return processTarget(metaObject.getValue("h.target"));
        }
        return target;
    }
}
