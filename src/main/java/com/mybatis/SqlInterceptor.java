package main.java.com.mybatis;

import main.java.com.mybatis.OptimisticLocker;
import main.java.com.mybatis.PluginUtil;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * @Author jiangyunxiong
 * @Date 2019/7/27 1:36 AM
 */

/**
 * @Intercepts告诉MyBatis当前插件用来拦截哪个对象的哪个方法 type：指四大对象拦截哪个对象
 * method：代表拦截哪个方法 ,在StatementHandler 中查看，需要拦截的方法
 * args：代表参数
 */

// 拦截StatementHandler类的prepare方法
@Intercepts({@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})})
public class SqlInterceptor implements Interceptor {

    // 乐观锁开关
    private static OptimisticLocker optimisticLocker;

    private static final String METHOD_NAME = "prepare";

    private Properties props = null;

    static {
        try {
            optimisticLocker = SqlInterceptor.class.getDeclaredMethod("optimisticValue").getAnnotation(OptimisticLocker.class);
        } catch (NoSuchMethodException | SecurityException e) {
            throw new RuntimeException("The plugin init faild.", e);
        }
    }

    @OptimisticLocker
    private void optimisticValue() {
    }

    /**
     * 拦截目标对象的目标方法的执行
     *
     * @param invocation
     * @return
     * @throws Throwable
     */
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        String versionColumn;
        String versionField;

        if (null == props) {
            versionColumn = "version";
            versionField = "version";
        } else {
            versionColumn = props.getProperty("versionColumn", "version");
            versionField = props.getProperty("versionField", "version");
        }

        String interceptMethodName = invocation.getMethod().getName();

        if (!METHOD_NAME.equals(interceptMethodName)) {
            //执行目标方法，并返回执行后的返回值
            return invocation.proceed();
        }

        StatementHandler handler = (StatementHandler) PluginUtil.processTarget(invocation.getTarget());

        MetaObject metaObject = SystemMetaObject.forObject(handler);
        // MappedStatement是保存了xxMapper.xml中一个sql语句节点的所有信息的包装类，可以通过它获取到节点中的所有信息。
        MappedStatement ms = (MappedStatement) metaObject.getValue("delegate.mappedStatement");
        SqlCommandType sqlCommandType = ms.getSqlCommandType();

        if (sqlCommandType != SqlCommandType.UPDATE) {
            //执行目标方法，并返回执行后的返回值
            return invocation.proceed();
        }

        // 执行的Sql的相关信息
        BoundSql boundSql = (BoundSql) metaObject.getValue("delegate.boundSql");

        boolean isOptimisticLocker = getOptimisticLocker(ms, boundSql);

        if (null != optimisticLocker && !isOptimisticLocker) {
            //执行目标方法，并返回执行后的返回值
            return invocation.proceed();
        }
        Object originalVersion = metaObject.getValue("delegate.boundSql.parameterObject." + versionField);

        if (originalVersion == null) {
            throw new BindingException("value of version field[" + versionField + "]can not be empty");
        }

        String originalSql = boundSql.getSql();
        // 在原sql语句加上 版本号的查询和更新
        originalSql = addVersionToSql(originalSql, versionColumn, originalVersion);
        // 修改sql语句
        metaObject.setValue("delegate.boundSql.sql", originalSql);

        //执行目标方法，并返回执行后的返回值
        return invocation.proceed();
    }

    /**
     * 包装目标对象：为目标对象创建一个代理对象
     *
     * @param target
     * @return
     */
    @Override
    public Object plugin(Object target) {
        if (target instanceof StatementHandler || target instanceof ParameterHandler) {
            // 返回为当前target创建的动态代理
            return Plugin.wrap(target, this);
        } else {
            return target;
        }
    }


    /**
     * 将插件注册时 的property属性设置进来
     *
     * @param properties
     */
    @Override
    public void setProperties(Properties properties) {
        if (null != properties && !properties.isEmpty()) props = properties;
    }

    private String addVersionToSql(String originalSql, String versionColumnName, Object originalVersion) {
        try {
            // 用于执行静态 SQL 语句并返回它所生成结果的对象。
            Statement statement = CCJSqlParserUtil.parse(originalSql);
            if (!(statement instanceof Update)) {
                return originalSql;
            }
            Update update = (Update) statement;
            // 如果没有版本号字段就创建一个
            if (!contains(update, versionColumnName)) {
                buildVersionExpression(update, versionColumnName);
            }
            Expression where = update.getWhere();
            if (where != null) {
                AndExpression andExpression = new AndExpression(where, buildVersionEquals(versionColumnName, originalVersion));
                update.setWhere(andExpression);
            } else {
                update.setWhere(buildVersionEquals(versionColumnName, originalVersion));
            }
            return statement.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return originalSql;
        }
    }

    private boolean getOptimisticLocker(MappedStatement ms, BoundSql boundSql) {
        Class[] paramCls = null;
        Object parameterObject = boundSql.getParameterObject();

        //1、处理@Param标记的参数
        if (parameterObject instanceof MapperMethod.ParamMap) {
            MapperMethod.ParamMap paramMapObject = (MapperMethod.ParamMap) parameterObject;
            if (null != paramMapObject && !paramMapObject.isEmpty()) {
                paramCls = new Class[paramMapObject.size() / 2];
                int len = paramMapObject.size() / 2;
                for (int i = 0; i < len; i++) {
                    Object index = paramMapObject.get("param" + (i + 1));
                    paramCls[i] = index.getClass();
                    if (List.class.isAssignableFrom(paramCls[i])) {
                        return false;
                    }
                }
            }
            //2、处理Map类型参数
        } else if (parameterObject instanceof Map) {//不支持批量
            @SuppressWarnings("rawtypes")
            Map map = (Map) parameterObject;
            if (map.get("list") != null || map.get("array") != null) {
                return false;
            } else {
                paramCls = new Class<?>[]{Map.class};
            }
            // 3、处理POJO实体对象类型的参数
        } else {
            paramCls = new Class<?>[]{parameterObject.getClass()};
        }
        Class<?> mapper = getMapper(ms);
        if (mapper != null) {
            Method m;
            try {
                m = mapper.getDeclaredMethod(getMapperShortId(ms), paramCls);
            } catch (NoSuchMethodException | SecurityException e) {
                throw new RuntimeException("The Map type param error." + e, e);
            }
            OptimisticLocker optimisticLocker = m.getAnnotation(OptimisticLocker.class);
            if (null == optimisticLocker) {
                return false;
            }
            return true;
        } else {
            throw new RuntimeException("Config info error, maybe you have not config the Mapper interface");
        }
    }

    // 判断update 语句更新的字段中有没有版本号这个字段
    private boolean contains(Update update, String versionColumnName) {
        List<Column> columns = update.getColumns();
        for (Column column : columns) {
            if (column.getColumnName().equalsIgnoreCase(versionColumnName)) {
                return true;
            }
        }
        return false;
    }

    private Class<?> getMapper(MappedStatement ms) {
        String namespace = getMapperNamespace(ms);
        Collection<Class<?>> mappers = ms.getConfiguration().getMapperRegistry().getMappers();
        for (Class<?> clazz : mappers) {
            if (clazz.getName().equals(namespace)) {
                return clazz;
            }
        }
        return null;
    }

    private String getMapperNamespace(MappedStatement ms) {
        String id = ms.getId();
        int pos = id.lastIndexOf(".");
        return id.substring(0, pos);
    }

    private String getMapperShortId(MappedStatement ms) {
        String id = ms.getId();
        int pos = id.lastIndexOf(".");
        return id.substring(pos + 1);
    }

    /**
     * 添加版本号字段
     *
     * @param update
     * @param versionColumnName
     */
    private void buildVersionExpression(Update update, String versionColumnName) {
        // 添加版本号字段
        List<Column> columns = update.getColumns();
        Column versionColumn = new Column();
        versionColumn.setColumnName(versionColumnName);
        columns.add(versionColumn);

        // 版本号 + 1
        List<Expression> expressions = update.getExpressions();
        Addition add = new Addition();
        add.setLeftExpression(versionColumn);
        add.setRightExpression(new LongValue(1));
        expressions.add(add);

    }


    /**
     * 匹配版本号，判断是否被其他线程修改过
     *
     * @param versionColumnName
     * @param originalVersion
     * @return
     */
    private Expression buildVersionEquals(String versionColumnName, Object originalVersion) {
        EqualsTo equalsTo = new EqualsTo();
        Column column = new Column();
        column.setColumnName(versionColumnName);
        equalsTo.setLeftExpression(column);
        LongValue value = new LongValue(originalVersion.toString());
        equalsTo.setRightExpression(value);
        return value;

    }
}