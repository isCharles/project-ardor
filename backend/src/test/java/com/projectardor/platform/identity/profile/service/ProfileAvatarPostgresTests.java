package com.projectardor.platform.identity.profile.service;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.projectardor.platform.identity.profile.repository.ProfileAvatarStore;

@EnabledIfEnvironmentVariable(named = "ARDOR_ACCEPTANCE_DISPOSABLE_DB", matches = "true")
class ProfileAvatarPostgresTests {
    @Test void freshAndPopulatedUpgradePreserveOtherAccountsAndExistingProfiles() throws Exception {
        String url = System.getenv().getOrDefault("ARDOR_FENCING_TEST_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/ardor");
        var root = new JdbcTemplate(new DriverManagerDataSource(url, "ardor", "acceptance-only"));
        for (boolean upgrade : new boolean[]{false, true}) {
            String schema = "ardor_avatar_" + UUID.randomUUID().toString().replace("-", "");
            root.execute("CREATE SCHEMA " + schema);
            try {
                var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public", "ardor", "acceptance-only");
                if (upgrade) Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("65").load().migrate();
                else Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
                var jdbc = new JdbcTemplate(source);
                UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
                for (UUID id : new UUID[]{owner, other}) jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,?)", id, id + "@ardor.invalid", "synthetic-only");
                var before = jdbc.queryForList("SELECT id,email,password_hash FROM users ORDER BY id");
                if (upgrade) Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
                assertThat(jdbc.queryForList("SELECT id,email,password_hash FROM users ORDER BY id")).isEqualTo(before);
                var store = new ProfileAvatarStore(jdbc);
                var service = new ProfileAvatarService(store, new AvatarImageNormalizer());
                assertThat(service.read(owner).imageDataUrl()).isNull();
                var uploaded = service.upload(owner, owner, ProfileAvatarTests.image(400, 200, "jpeg"));
                assertThat(uploaded.imageDataUrl()).startsWith("data:image/png;base64,");
                assertThat(service.read(owner)).isEqualTo(uploaded);
                assertThat(service.read(other).imageDataUrl()).isNull();
                assertThatThrownBy(() -> service.upload(other, owner, ProfileAvatarTests.image(20, 20, "png"))).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> service.remove(other, owner)).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> service.upload(owner, owner, new byte[]{1,2,3})).isInstanceOf(IllegalArgumentException.class);
                assertThat(service.read(owner)).isEqualTo(uploaded);
                var replaced = service.upload(owner, owner, ProfileAvatarTests.image(200, 400, "png"));
                assertThat(service.read(owner)).isEqualTo(replaced);
                service.upload(other, other, ProfileAvatarTests.image(10, 10, "png"));
                service.remove(owner, owner);
                assertThat(service.read(owner).imageDataUrl()).isNull();
                assertThat(service.read(other).imageDataUrl()).isNotNull();
            } finally { root.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }
}
