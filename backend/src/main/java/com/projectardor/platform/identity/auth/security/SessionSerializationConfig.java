package com.projectardor.platform.identity.auth.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.io.ObjectStreamField;
import java.util.Map;
import com.projectardor.platform.identity.auth.domain.UserRole;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/** Reads pre-package-migration sessions; all new writes use current class names. */
@Configuration
public class SessionSerializationConfig {
    private static final Map<String, Class<?>> LEGACY_TYPES = Map.of(
            "com.projectardor.auth.security.ArdorPrincipal", ArdorPrincipal.class,
            "com.projectardor.auth.domain.UserRole", UserRole.class);

    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new JdkSerializationRedisSerializer(new SerializingConverter(), bytes -> {
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes)) {
                @Override
                protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
                    ObjectStreamClass stored = super.readClassDescriptor();
                    Class<?> replacement = LEGACY_TYPES.get(stored.getName());
                    if (replacement == null) return stored;
                    ObjectStreamClass local = ObjectStreamClass.lookup(replacement);
                    requireCompatible(stored, local);
                    return local;
                }
            }) {
                return input.readObject();
            } catch (IOException | ClassNotFoundException exception) {
                throw new SerializationException("Cannot deserialize session", exception);
            }
        });
    }

    private static void requireCompatible(ObjectStreamClass stored, ObjectStreamClass local) throws InvalidClassException {
        ObjectStreamField[] before = stored.getFields();
        ObjectStreamField[] after = local.getFields();
        if (stored.getSerialVersionUID() != local.getSerialVersionUID() || before.length != after.length)
            throw new InvalidClassException(stored.getName(), "Incompatible legacy session descriptor");
        for (int index = 0; index < before.length; index++) {
            String beforeType = before[index].getTypeString();
            if (beforeType != null) beforeType = beforeType.replace(
                    "Lcom/projectardor/auth/domain/UserRole;",
                    "Lcom/projectardor/platform/identity/auth/domain/UserRole;");
            if (!before[index].getName().equals(after[index].getName())
                    || before[index].getTypeCode() != after[index].getTypeCode()
                    || !java.util.Objects.equals(beforeType, after[index].getTypeString()))
                throw new InvalidClassException(stored.getName(), "Incompatible legacy session fields");
        }
    }
}
