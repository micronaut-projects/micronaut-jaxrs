package io.micronaut.jaxrs.servlet;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.type.Argument;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public class PaginatedCollection<T>  {
    @NonNull
    private final T items;

    public PaginatedCollection(@NonNull T items) {
        this.items = items;
    }

    /**
     * @return The paginated items
     */
    @NonNull
    public T getItems() {
        return items;
    }

    /**
     * Constructs a paginated collection argument for the given type.
     * @param type The type
     * @return The argument
     * @param <A> The generic type
     */
    @SuppressWarnings("unchecked")
    public static <A> @NonNull Argument<PaginatedCollection<A>> asArgument(@NonNull Class<A> type) {
        Argument<?> t = Argument.of(PaginatedCollection.class, type);
        return (Argument<PaginatedCollection<A>>) t;
    }
}
