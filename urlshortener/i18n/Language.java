package urlshortener.i18n;

public enum Language {
    EN("en", "En"),
    RU("ru", "Ру");

    private final String code;
    private final String buttonLabel;

    Language(String code, String buttonLabel) {
        this.code = code;
        this.buttonLabel = buttonLabel;
    }

    public String code() {
        return code;
    }

    public String buttonLabel() {
        return buttonLabel;
    }

    public static Language fromCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return EN;
        }
        String normalized = rawCode.trim().toLowerCase();
        return "ru".equals(normalized) ? RU : EN;
    }
}
