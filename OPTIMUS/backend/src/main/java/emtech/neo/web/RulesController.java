package one.neo.web;

import one.neo.rules.RulesService;
import one.neo.rules.RulesStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The rules API: the discovered Oracle rules (keys, joins, indexes...) kept in
 * rules.json. Refresh re-runs the dictionary queries so a newly added table
 * shows up on the page.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Rules (Oracle data dictionary -> rules.json)")
public class RulesController {

    private static final Logger log = LoggerFactory.getLogger(RulesController.class);

    private final RulesService rules;
    private final RulesStore store;

    public RulesController(RulesService rules, RulesStore store) {
        this.rules = rules;
        this.store = store;
    }

    @GetMapping("/rules")
    @Operation(summary = "The discovered rules catalog (rules.json)")
    public ResponseEntity<?> rules() {
        return ResponseEntity.ok(store.catalog());
    }

    @GetMapping("/rules/tables")
    @Operation(summary = "Tables currently configured for scanning")
    public ResponseEntity<?> tables() {
        return ResponseEntity.ok(Map.of("tables", rules.configuredTables()));
    }

    @PostMapping("/rules/refresh")
    @Operation(summary = "Re-query Oracle's data dictionary and rewrite rules.json (all rules)")
    public ResponseEntity<?> refresh(@RequestParam(required = false) String tables) {
        try {
            List<RulesService.TableRef> scope = null;
            if (tables != null && !tables.isBlank()) scope = RulesService.parseTables(tables);
            return ResponseEntity.ok(rules.refresh(scope));
        } catch (Exception e) {
            log.error("rules refresh failed", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/rules/groups/{id}/refresh")
    @Operation(summary = "Re-query ONE rule (e.g. aliases) and update only that group in rules.json")
    public ResponseEntity<?> refreshGroup(@PathVariable String id) {
        try {
            return ResponseEntity.ok(rules.refreshGroup(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            log.error("rule refresh failed for {}", id, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
