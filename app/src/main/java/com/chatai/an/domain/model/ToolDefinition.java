package com.chatai.an.domain.model;

public class ToolDefinition {
    private final String name;
    private final String description;
    private final String inputSchemaJson;
    private final ToolPermission permission;
    private final ToolCategory category;
    private final boolean mutating;

    public ToolDefinition(
            String name,
            String description,
            String inputSchemaJson,
            ToolPermission permission
    ) {
        this(name, description, inputSchemaJson, permission, ToolCategory.UTILITY, permission == ToolPermission.REQUIRE_CONFIRMATION);
    }

    public ToolDefinition(
            String name,
            String description,
            String inputSchemaJson,
            ToolPermission permission,
            ToolCategory category,
            boolean mutating
    ) {
        this.name = name;
        this.description = description;
        this.inputSchemaJson = inputSchemaJson;
        this.permission = permission != null ? permission : ToolPermission.REQUIRE_CONFIRMATION;
        this.category = category != null ? category : ToolCategory.UTILITY;
        this.mutating = mutating;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getInputSchemaJson() {
        return inputSchemaJson;
    }

    public ToolPermission getPermission() {
        return permission;
    }

    public ToolCategory getCategory() {
        return category;
    }

    public boolean isMutating() {
        return mutating;
    }
}
