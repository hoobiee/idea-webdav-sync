package sync;

import java.util.*;

/** Single source of truth for supported paths and selectable sync scopes. */
public enum SyncCategory {
    KEYMAPS("keymaps", "快捷键", "Keymaps"),
    COLORS("colors", "配色", "Colors"),
    CODESTYLES("codestyles", "代码风格", "Code styles"),
    TEMPLATES("templates", "实时模板", "Live templates"),
    INSPECTION("inspection", "代码检查", "Inspections"),
    PLUGINS("plugins.txt", "插件清单", "Plugin list"),
    FILE_TEMPLATES("fileTemplates", "文件模板", "File templates"),
    DICTIONARIES("dictionaries", "拼写词典", "Dictionaries"),
    QUICKLISTS("quicklists", "快捷操作列表", "Quick lists");

    public final String root;
    private final String zh, en;
    SyncCategory(String root, String zh, String en) { this.root = root; this.zh = zh; this.en = en; }
    public String label(boolean chinese) { return chinese ? zh : en; }
    private static final Set<String> TEXT_EXTENSIONS = Set.of("ft", "java", "kt", "kts", "groovy", "scala", "xml", "html", "htm", "css", "js", "ts", "jsx", "tsx", "json", "yaml", "yml", "properties", "txt", "md", "py", "sh", "sql", "php", "rb", "c", "h", "cpp", "cs", "go", "rs", "vue", "svg");
    public static SyncCategory ofPath(String path) {
        if (path.equals("plugins.txt")) return PLUGINS;
        int slash = path.indexOf('/');
        if (slash < 1) return null;
        String root = path.substring(0, slash);
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        for (SyncCategory c : values()) {
            if (c == PLUGINS || !c.root.equals(root)) continue;
            if (c == FILE_TEMPLATES) return TEXT_EXTENSIONS.contains(ext) ? c : null;
            return ext.equals("xml") || (c == COLORS && ext.equals("icls")) ? c : null;
        }
        return null;
    }
    public static Set<SyncCategory> parse(String value) {
        EnumSet<SyncCategory> result = EnumSet.noneOf(SyncCategory.class);
        for (String part : value.split(",")) {
            try { result.add(valueOf(part)); } catch (IllegalArgumentException ignored) { }
        }
        return result;
    }
    public static String serialize(Set<SyncCategory> scope) {
        // A nonempty sentinel survives IDE string settings normalization.
        if (scope.isEmpty()) return "NONE";
        return Arrays.stream(values()).filter(scope::contains).map(Enum::name).collect(java.util.stream.Collectors.joining(","));
    }
    public static void requireSelection(Set<SyncCategory> scope) {
        if (scope.isEmpty()) throw new IllegalArgumentException("Select at least one category.");
    }
}
