package org.wyrdsekai.core.recipe;

/** Thrown when a recipe manifest or leaf recipe is structurally invalid. */
public class RecipeValidationException extends RuntimeException {
    public RecipeValidationException(String message) {
        super(message);
    }
}
