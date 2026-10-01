package dev.gamjaoj;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One durable table-creation state per user/parent, shared by all accepted solutions. */
@Component
final class NotionTableRegistry {
    final JdbcClient jdbc;final TransactionTemplate tx;
    NotionTableRegistry(JdbcClient jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);}
    JsonNode resolve(UUID user,String token,JsonNode target,ExportRemote remote,Runnable deliveryFence){
        String parent=target.path("id").asText();UUID lease=UUID.randomUUID();
        ObjectNode state=tx.execute(s->{
            jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE").param(user).query(UUID.class).single();
            if(jdbc.sql("SELECT count(*) FROM notion_export_table WHERE user_id=? AND parent_id=?").param(user).param(parent).query(Integer.class).single()==0)
                jdbc.sql("INSERT INTO notion_export_table(user_id,parent_id) VALUES (?,?)").param(user).param(parent).update();
            if(jdbc.sql("UPDATE notion_export_table SET lease_token=?,lease_until=? WHERE user_id=? AND parent_id=? AND (lease_token IS NULL OR lease_until<?)")
                .param(lease).param(OffsetDateTime.now().plusSeconds(60)).param(user).param(parent).param(OffsetDateTime.now()).update()!=1)
                throw new ExportRemote.Failure("CONNECTION_BUSY",true);
            return (ObjectNode)JudgeJson.parse(jdbc.sql("SELECT remote_json FROM notion_export_table WHERE user_id=? AND parent_id=?").param(user).param(parent).query(String.class).single());
        });
        Runnable fence=()->{
            deliveryFence.run();
            if(jdbc.sql("UPDATE notion_export_table SET lease_until=? WHERE user_id=? AND parent_id=? AND lease_token=? AND lease_until>?")
                .param(OffsetDateTime.now().plusSeconds(60)).param(user).param(parent).param(lease).param(OffsetDateTime.now()).update()!=1)
                throw new ExportRemote.Failure("CONNECTION_CHANGED",false);
        };
        try{
            String source=remote.notionTableSource(token,target,state,next->{
                fence.run();
                if(jdbc.sql("UPDATE notion_export_table SET remote_json=? WHERE user_id=? AND parent_id=? AND lease_token=?")
                    .param(JudgeJson.canonical(next)).param(user).param(parent).param(lease).update()!=1)throw new ExportRemote.Failure("CONNECTION_CHANGED",false);
            },fence);
            return ((ObjectNode)target.deepCopy()).put("dataSourceId",source);
        }finally{
            jdbc.sql("UPDATE notion_export_table SET lease_token=NULL,lease_until=NULL WHERE user_id=? AND parent_id=? AND lease_token=?")
                .param(user).param(parent).param(lease).update();
        }
    }
}
