package core.persist;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import util.Log;

/**
 * \u672c\u5730 SQLite \u6301\u4e45\u5316\u64cd\u4f5c\u65e5\u5fd7\uff1a\u5206\u7ec4 \u2192 \u4f1a\u8bdd \u2192 \u6761\u76ee\u3002
 */
public final class OperationLogStore {

    private static final Object LOCK = new Object();
    private static volatile boolean initDone;
    private static volatile boolean available;
    private static Connection connection;

    private OperationLogStore() {
    }

    public static File getDataDirectory() {
        // GSL5 CLI fork: honor -Dgsl5.data.dir so the audit store can live inside a task directory
        // (wrapper passes <task>/logs); default stays ~/.gsl5 for GUI users.
        File dir = new File(System.getProperty("gsl5.data.dir",
                new File(System.getProperty("user.home"), ".gsl5").getPath()));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.error("OperationLogStore: cannot mkdir " + dir.getAbsolutePath());
        }
        return dir;
    }

    public static boolean isAvailable() {
        return available;
    }

    public static boolean init() {
        synchronized (LOCK) {
            if (initDone) {
                return available;
            }
            initDone = true;
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                Log.error(e);
                available = false;
                return false;
            }
            try {
                File dbFile = new File(getDataDirectory(), "operation_audit.db");
                String url = "jdbc:sqlite:" + dbFile.getAbsolutePath().replace('\\', '/');
                connection = DriverManager.getConnection(url);
                try (Statement st = connection.createStatement()) {
                    st.execute("PRAGMA foreign_keys = ON");
                    st.execute("PRAGMA journal_mode = WAL");
                    st.execute("CREATE TABLE IF NOT EXISTS log_groups ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + "name TEXT NOT NULL UNIQUE)");
                    st.execute("CREATE TABLE IF NOT EXISTS log_sessions ("
                            + "id TEXT PRIMARY KEY,"
                            + "group_id INTEGER NOT NULL REFERENCES log_groups(id),"
                            + "started_at INTEGER NOT NULL)");
                    st.execute("CREATE TABLE IF NOT EXISTS log_entries ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + "session_id TEXT NOT NULL REFERENCES log_sessions(id),"
                            + "seq INTEGER NOT NULL,"
                            + "body TEXT NOT NULL)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_log_entries_session ON log_entries(session_id, seq)");
                    st.execute("CREATE TABLE IF NOT EXISTS log_kv (k TEXT PRIMARY KEY, v TEXT)");
                }
                available = true;
                return true;
            } catch (Exception e) {
                Log.error(e);
                available = false;
                closeQuietly();
                return false;
            }
        }
    }

    private static void closeQuietly() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Exception ignored) {
        }
        connection = null;
    }

    public static long ensureGroup(String name) {
        if (name == null || name.trim().isEmpty()) {
            name = "\u9ed8\u8ba4";
        }
        name = name.trim();
        synchronized (LOCK) {
            if (!available) {
                return -1;
            }
            try {
                try (PreparedStatement sel = connection.prepareStatement(
                        "SELECT id FROM log_groups WHERE name = ?")) {
                    sel.setString(1, name);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (rs.next()) {
                            return rs.getLong(1);
                        }
                    }
                }
                try (PreparedStatement ins = connection.prepareStatement(
                        "INSERT INTO log_groups(name) VALUES (?)")) {
                    ins.setString(1, name);
                    ins.executeUpdate();
                } catch (java.sql.SQLException insertEx) {
                    try (PreparedStatement sel2 = connection.prepareStatement(
                            "SELECT id FROM log_groups WHERE name = ?")) {
                        sel2.setString(1, name);
                        try (ResultSet rs2 = sel2.executeQuery()) {
                            if (rs2.next()) {
                                return rs2.getLong(1);
                            }
                        }
                    }
                }
                try (PreparedStatement sel = connection.prepareStatement(
                        "SELECT id FROM log_groups WHERE name = ?")) {
                    sel.setString(1, name);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (rs.next()) {
                            return rs.getLong(1);
                        }
                    }
                }
            } catch (Exception e) {
                Log.error(e);
            }
        }
        return -1;
    }

    public static boolean groupExists(long groupId) {
        synchronized (LOCK) {
            if (!available || groupId <= 0) {
                return false;
            }
            try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM log_groups WHERE id = ?")) {
                ps.setLong(1, groupId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (Exception e) {
                Log.error(e);
                return false;
            }
        }
    }

    public static List<GroupRow> listGroups() {
        List<GroupRow> out = new ArrayList<>();
        synchronized (LOCK) {
            if (!available) {
                return out;
            }
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, name FROM log_groups ORDER BY id ASC")) {
                while (rs.next()) {
                    out.add(new GroupRow(rs.getLong(1), rs.getString(2)));
                }
            } catch (Exception e) {
                Log.error(e);
            }
        }
        return out;
    }

    public static String createSession(long groupId, long startedAtMs) {
        if (groupId <= 0) {
            return null;
        }
        String sid = UUID.randomUUID().toString().replace("-", "");
        synchronized (LOCK) {
            if (!available) {
                return null;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO log_sessions(id, group_id, started_at) VALUES (?,?,?)")) {
                ps.setString(1, sid);
                ps.setLong(2, groupId);
                ps.setLong(3, startedAtMs);
                ps.executeUpdate();
                return sid;
            } catch (Exception e) {
                Log.error(e);
                return null;
            }
        }
    }

    public static List<SessionRow> listSessions(long groupId) {
        List<SessionRow> out = new ArrayList<>();
        synchronized (LOCK) {
            if (!available) {
                return out;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, group_id, started_at FROM log_sessions WHERE group_id = ? ORDER BY started_at DESC")) {
                ps.setLong(1, groupId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new SessionRow(rs.getString(1), rs.getLong(2), rs.getLong(3)));
                    }
                }
            } catch (Exception e) {
                Log.error(e);
            }
        }
        return out;
    }

    public static void appendEntry(String sessionId, String body) {
        if (sessionId == null || body == null || body.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            if (!available) {
                return;
            }
            try {
                int nextSeq = 1;
                try (PreparedStatement mx = connection.prepareStatement(
                        "SELECT COALESCE(MAX(seq),0)+1 FROM log_entries WHERE session_id = ?")) {
                    mx.setString(1, sessionId);
                    try (ResultSet rs = mx.executeQuery()) {
                        if (rs.next()) {
                            nextSeq = rs.getInt(1);
                        }
                    }
                }
                try (PreparedStatement ins = connection.prepareStatement(
                        "INSERT INTO log_entries(session_id, seq, body) VALUES (?,?,?)")) {
                    ins.setString(1, sessionId);
                    ins.setInt(2, nextSeq);
                    ins.setString(3, body);
                    ins.executeUpdate();
                }
            } catch (Exception e) {
                Log.error(e);
            }
        }
    }

    public static String loadSessionText(String sessionId) {
        if (sessionId == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            if (!available) {
                return "";
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT body FROM log_entries WHERE session_id = ? ORDER BY seq ASC")) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(rs.getString(1));
                    }
                }
            } catch (Exception e) {
                Log.error(e);
            }
        }
        return sb.toString();
    }

    public static void clearSessionEntries(String sessionId) {
        if (sessionId == null) {
            return;
        }
        synchronized (LOCK) {
            if (!available) {
                return;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM log_entries WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            } catch (Exception e) {
                Log.error(e);
            }
        }
    }

    public static String getKv(String key) {
        synchronized (LOCK) {
            if (!available || key == null) {
                return null;
            }
            try (PreparedStatement ps = connection.prepareStatement("SELECT v FROM log_kv WHERE k = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
            } catch (Exception e) {
                Log.error(e);
            }
        }
        return null;
    }

    public static void setKv(String key, String value) {
        synchronized (LOCK) {
            if (!available || key == null) {
                return;
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO log_kv(k,v) VALUES(?,?)")) {
                ps.setString(1, key);
                ps.setString(2, value == null ? "" : value);
                ps.executeUpdate();
            } catch (Exception e) {
                Log.error(e);
            }
        }
    }

    public static final class GroupRow {
        public final long id;
        public final String name;

        public GroupRow(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class SessionRow {
        public final String id;
        public final long groupId;
        public final long startedAtMs;

        public SessionRow(String id, long groupId, long startedAtMs) {
            this.id = id;
            this.groupId = groupId;
            this.startedAtMs = startedAtMs;
        }
    }
}
