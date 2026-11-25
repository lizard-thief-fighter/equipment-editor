package equipment_editor;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class EquipmentConfig {
    private EquipmentConfig() {}

    static final Logger LOGGER = LogUtils.getLogger();

    public static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("equipment_editor.json");
    private static final String EXAMPLE_RESOURCE = "/equipment_editor-example.json";
    private static final Gson GSON = new Gson();
    private static final List<Field> RULE_FIELDS = Arrays.stream(Rule.class.getFields())
        .filter(field -> !Modifier.isStatic(field.getModifiers()))
        .toList();
    private static final Set<String> RULE_KEYS = RULE_FIELDS.stream()
        .map(Field::getName)
        .collect(Collectors.toUnmodifiableSet());

    public static List<Rule> RULES = new ArrayList<>();

    public static void load() {
        try {
            if (!Files.exists(CONFIG_PATH)) writeExample();
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                JsonElement json = JsonParser.parseReader(reader);
                if (!json.isJsonObject()) {
                    throw new JsonParseException("Expected an object with a \"rules\" array");
                }
                warnUnknownKeys(json.getAsJsonObject());
                ConfigFile config = GSON.fromJson(json, ConfigFile.class);
                RULES = config == null || config.rules == null ? new ArrayList<>() : config.rules;
            }
        } catch (Exception e) {
            LOGGER.error("Could not load {}", CONFIG_PATH, e);
            RULES = new ArrayList<>();
        }
        EquipmentModifier.rebuildRuleIndex();
    }

    private static void writeExample() throws IOException {
        Files.createDirectories(CONFIG_PATH.getParent());
        try (InputStream example = EquipmentConfig.class.getResourceAsStream(EXAMPLE_RESOURCE)) {
            if (example == null) throw new IOException("Bundled " + EXAMPLE_RESOURCE + " is missing");
            Files.copy(example, CONFIG_PATH);
        }
    }

    // gson ignores unknown keys, warn instead
    private static void warnUnknownKeys(JsonObject root) {
        for (String key : root.keySet()) {
            if (!key.equals("rules")) LOGGER.warn("Unknown key \"{}\" in {} is ignored", key, CONFIG_PATH);
        }
        JsonElement rules = root.get("rules");
        if (rules == null || !rules.isJsonArray()) return;
        for (JsonElement rule : rules.getAsJsonArray()) {
            if (!rule.isJsonObject()) continue;
            for (String key : rule.getAsJsonObject().keySet()) {
                if (!RULE_KEYS.contains(key)) {
                    LOGGER.warn("Unknown rule key \"{}\" in {} is ignored", key, CONFIG_PATH);
                }
            }
        }
    }

    private static final class ConfigFile {
        List<Rule> rules;
    }

    public static final class Rule {
        public List<String> items = new ArrayList<>();
        public Integer durability;
        public Boolean unbreakable;
        public Integer maxStackSize;
        public Boolean fireResistant;
        public String rarity;
        public Double attackDamage;
        public Double attackSpeed;
        public Double armor;
        public Double armorToughness;
        public Double knockbackResistance;
        public Double entityInteractionRange;
        public Double blockInteractionRange;
        public Double movementSpeed;
        public Double attackKnockback;
        public Double miningSpeed;
        public Double miningSpeedMultiplier;
        public Double durabilityMultiplier;
        public Map<String, Double> attributes = new HashMap<>();
        public Map<String, Integer> enchantments = new HashMap<>();

        void mergeFrom(Rule source) {
            try {
                for (Field field : RULE_FIELDS) {
                    Object value = field.get(source);
                    if (value == null || value instanceof Collection || value instanceof Map) continue;
                    field.set(this, value);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            if (source.attributes != null) attributes.putAll(source.attributes);
            if (source.enchantments != null) enchantments.putAll(source.enchantments);
        }
    }
}
