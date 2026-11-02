package com.voucherx.commerce;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.sql.Statement;
public final class JdbcIds {
    private JdbcIds() {}
    public static long insert(JdbcTemplate db,String sql,Object... args) {
        var keys=new GeneratedKeyHolder();
        db.update(connection->{
            var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            return statement;
        },keys);
        if(keys.getKey()==null) throw new IllegalStateException("数据库未返回主键");
        return keys.getKey().longValue();
    }
}
