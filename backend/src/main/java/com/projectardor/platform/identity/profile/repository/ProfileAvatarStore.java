package com.projectardor.platform.identity.profile.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileAvatarStore {
    private final JdbcTemplate jdbc;
    public ProfileAvatarStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<Image> find(UUID owner) {
        return jdbc.query("SELECT png_data,updated_at FROM profile_avatars WHERE user_id=?",
                (rs, row) -> new Image(rs.getBytes(1), rs.getTimestamp(2).toInstant()), owner).stream().findFirst();
    }
    public Image save(UUID owner, byte[] png) {
        return jdbc.queryForObject("""
                INSERT INTO profile_avatars(user_id,png_data) VALUES(?,?)
                ON CONFLICT(user_id) DO UPDATE SET png_data=EXCLUDED.png_data,updated_at=CURRENT_TIMESTAMP
                RETURNING png_data,updated_at
                """, (rs, row) -> new Image(rs.getBytes(1), rs.getTimestamp(2).toInstant()), owner, png);
    }
    public void remove(UUID owner) { jdbc.update("DELETE FROM profile_avatars WHERE user_id=?", owner); }
    public record Image(byte[] png, Instant updatedAt) {
        public Image { png = png.clone(); }
        @Override public byte[] png() { return png.clone(); }
    }
}
