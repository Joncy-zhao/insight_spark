package com.insightspark.core.security;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLDropTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.util.JdbcConstants;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.List;
import java.util.Properties;

/**
 * 核心：MyBatis SQL 安全拦截器 (防止 AI 乱搞破坏)
 * 拦截阶段：在准备 Statement 之前 (prepare)
 */
@Component
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
})
public class SqlSecurityInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(SqlSecurityInterceptor.class);

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 1. 获取 StatementHandler，它包含了当前要执行的 SQL 信息
        StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
        BoundSql boundSql = statementHandler.getBoundSql();
        String originalSql = boundSql.getSql();

        // 2. 检查是否为空
        if (originalSql == null || originalSql.trim().isEmpty()) {
            return invocation.proceed(); // 让它继续执行
        }

        log.info("🛡️ [安全审计] 准备执行 SQL: {}", originalSql);

        try {
            // 3. 使用 Druid 的解析引擎，将字符串 SQL 转换成抽象语法树 (AST)
            //    这里指定数据库类型为 MySQL
            List<SQLStatement> stmtList = SQLUtils.parseStatements(originalSql, JdbcConstants.MYSQL);

            // 4. 遍历所有解析出来的 SQL 语句，进行严格审查
            for (SQLStatement stmt : stmtList) {
                // 规则 1：绝对禁止 DROP TABLE (删表)
                if (stmt instanceof SQLDropTableStatement) {
                    log.error("❌ 拦截到危险操作：禁止执行 DROP TABLE！SQL: {}", originalSql);
                    throw new RuntimeException("安全告警：系统已拦截恶意删表操作 (DROP TABLE)！");
                }

                // 规则 2：绝对禁止 DELETE (删数据)
                // 在 BI 系统中，数据只能被分析，绝不能被普通的查询接口删除
                if (stmt instanceof SQLDeleteStatement) {
                    log.error("❌ 拦截到危险操作：禁止执行 DELETE！SQL: {}", originalSql);
                    throw new RuntimeException("安全告警：系统已拦截恶意删数据操作 (DELETE)！");
                }

                // 规则 3：绝对禁止 UPDATE (改数据)
                if (stmt instanceof SQLUpdateStatement) {
                    log.error("❌ 拦截到危险操作：禁止执行 UPDATE！SQL: {}", originalSql);
                    throw new RuntimeException("安全告警：系统已拦截恶意改数据操作 (UPDATE)！");
                }
            }

            // 5. TODO: 后续 Sprint 可以继续添加：敏感字段脱敏(SELECT 拦截)、越权访问(加 WHERE user_id) 等更高级的规则

        } catch (com.alibaba.druid.sql.parser.ParserException e) {
            // 如果连解析都解析不了，说明 SQL 语法有问题，也可能有注入风险，直接拦截
            log.error("❌ 拦截到语法错误或无法解析的异常 SQL: {}", originalSql);
            throw new RuntimeException("安全告警：检测到非法或无法解析的 SQL 语法！");
        }

        // 6. 如果所有检查都通过了，放行，让原本的逻辑继续执行
        return invocation.proceed();
    }

    @Override
    public Object plugin(Object target) {
        // 必须写这句，将这个拦截器包装进去
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // 可以在这里接收配置文件传来的参数
    }
}