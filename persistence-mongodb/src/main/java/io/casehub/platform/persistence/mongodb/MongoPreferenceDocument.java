package io.casehub.platform.persistence.mongodb;

import com.mongodb.client.model.Filters;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.Collections;
import java.util.List;

/**
 * MongoDB document for a single preference value.
 *
 * <p>Stored in {@code platform_preferences}. The {@code _id} is a compound natural key
 * {@code "{scope}|{namespace}|{name}|{subKey}"} — uniqueness is enforced at the BSON level
 * with no separate unique index. The delimiter {@code |} is safe because scope uses {@code /},
 * namespace and name use alphanumeric characters only.
 *
 * <p>The {@code scope} field is indexed (created at startup by {@link MongoPreferenceIndexes})
 * to serve the ancestor {@code $in} query efficiently.
 *
 * <p><b>Root scope behaviour:</b> {@code Path.root().value()} is {@code ""} (empty string).
 * When resolving a root-scoped request, the ancestors list is {@code [""]} and the query
 * filters on {@code scope == ""}. This is consistent with {@code JpaPreferenceProvider}.
 * A document stored at root scope must have {@code scope = ""} to be matched.
 */
@MongoEntity(collection = "platform_preferences")
public class MongoPreferenceDocument extends PanacheMongoEntityBase {

    @BsonId
    public String id;
    public String tenancyId;


    public String scope;
    public String namespace;
    public String name;
    /** Empty string for single-value preferences; non-empty for multi-value (parity with JPA). */
    public String subKey = "";
    public String value;

    public static String compoundId(final String tenancyId, final String scope, final String namespace,
                                    final String name, final String subKey) {
        return tenancyId + "|" + scope + "|" + namespace + "|" + name + "|" + subKey;
    }

    static List<MongoPreferenceDocument> findByScopes(final String tenancyId, final List<String> scopes) {
        if (scopes.isEmpty()) {return Collections.emptyList();}
        return list(Filters.and(Filters.eq("tenancyId", tenancyId), Filters.in("scope", scopes)));
    }
}
