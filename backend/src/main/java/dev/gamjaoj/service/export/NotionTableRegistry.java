package dev.gamjaoj.service.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.gamjaoj.infrastructure.export.ExportRemote;
import dev.gamjaoj.repository.export.NotionTableRegistryRepository;
import dev.gamjaoj.support.JudgeJson;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One durable table-creation state per user/parent, shared by all accepted solutions. */
@Service
public final class NotionTableRegistry {
  final NotionTableRegistryRepository repository;
  final TransactionTemplate tx;

  public NotionTableRegistry(
      NotionTableRegistryRepository repository, PlatformTransactionManager manager) {
    this.repository = repository;
    this.tx = new TransactionTemplate(manager);
  }

  public JsonNode resolve(
      UUID user, String token, JsonNode target, ExportRemote remote, Runnable deliveryFence) {
    String parent = target.path("id").asText();
    UUID lease = UUID.randomUUID();
    ObjectNode state =
        tx.execute(
            s -> {
              repository.resolveAppUser(user);
              if (repository.resolveNotionExportTable(user, parent) == 0)
                repository.resolveNotionExportTable2(user, parent);
              if (repository.resolveNotionExportTable3(
                      lease,
                      OffsetDateTime.now().plusSeconds(60),
                      user,
                      parent,
                      OffsetDateTime.now())
                  != 1) throw new ExportRemote.Failure("CONNECTION_BUSY", true);
              return (ObjectNode)
                  JudgeJson.parse(repository.resolveNotionExportTable4(user, parent));
            });
    Runnable fence =
        () -> {
          deliveryFence.run();
          if (repository.resolveNotionExportTable5(
                  OffsetDateTime.now().plusSeconds(60), user, parent, lease, OffsetDateTime.now())
              != 1) throw new ExportRemote.Failure("CONNECTION_CHANGED", false);
        };
    try {
      String source =
          remote.notionTableSource(
              token,
              target,
              state,
              next -> {
                fence.run();
                if (repository.resolveNotionExportTable6(
                        JudgeJson.canonical(next), user, parent, lease)
                    != 1) throw new ExportRemote.Failure("CONNECTION_CHANGED", false);
              },
              fence);
      return ((ObjectNode) target.deepCopy()).put("dataSourceId", source);
    } finally {
      repository.resolveNotionExportTable7(user, parent, lease);
    }
  }
}
