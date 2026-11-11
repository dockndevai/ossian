package io.github.dockndevai.ossian.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V13, the migration that adds {@code api_keys.agent_id}, applied the two ways it will be.
 *
 * <p>On an empty database, which is every new install and every test; and on one that already
 * holds keys issued by the previous schema, which is every upgrade. The second is the one that
 * matters: a migration that works on a clean database and fails on a populated one is found by
 * the first operator who upgrades. Both run Flyway directly against the real scripts, with no
 * Spring context, so what is proved is the SQL and nothing else.
 */
@Testcontainers
class ApiKeyAgentBindingMigrationTests {

	private static final String BEFORE = "12";

	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
			DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
		.withDatabaseName("postgres")
		.withUsername("ossian")
		.withPassword("ossian");

	/** A fresh, isolated database per test, so one test's migrations never leak into the next. */
	private static String freshDatabase() throws SQLException {
		String name = "mig_" + UUID.randomUUID().toString().replace("-", "");
		try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword()); var st = c.createStatement()) {
			st.execute("create database " + name);
		}
		return POSTGRES.getJdbcUrl().replaceFirst("/postgres", "/" + name);
	}

	private static Flyway flyway(String url, String target) {
		return Flyway.configure()
			.dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.target(target)
			.load();
	}

	private static Connection open(String url) throws SQLException {
		return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	@Test
	@DisplayName("applies on a clean database: nullable agent_id, bounded to 128, partial index on bound keys")
	void appliesOnCleanDatabase() throws Exception {
		String url = freshDatabase();

		var result = flyway(url, "latest").migrate();

		assertThat(result.success).isTrue();
		try (Connection c = open(url); var st = c.createStatement()) {
			var column = st.executeQuery("""
					select is_nullable, character_maximum_length, column_default from information_schema.columns
					where table_name = 'api_keys' and column_name = 'agent_id'
					""");
			assertThat(column.next()).as("agent_id column exists").isTrue();
			assertThat(column.getString("is_nullable")).isEqualTo("YES");
			assertThat(column.getInt("character_maximum_length")).isEqualTo(128);
			assertThat(column.getString("column_default")).isNull();

			var index = st.executeQuery(
					"select indexdef from pg_indexes where tablename = 'api_keys' and indexname = 'idx_api_keys_agent'");
			assertThat(index.next()).as("index exists").isTrue();
			assertThat(index.getString("indexdef")).containsIgnoringCase("(agent_id)")
				.containsIgnoringCase("WHERE (agent_id IS NOT NULL)");

			assertThat(st.executeQuery("select max(version::int) from flyway_schema_history where success")
				.next()).isTrue();
		}
	}

	@Test
	@DisplayName("applies on a database that already holds keys, which stay valid and come out unbound")
	void appliesOnDatabaseWithExistingKeys() throws Exception {
		String url = freshDatabase();
		flyway(url, BEFORE).migrate();
		List<String> existing = List.of("nightly-import", "support-agent", "dashboards");
		try (Connection c = open(url); var st = c.createStatement()) {
			// The api_keys shape as the previous release left it: no agent_id yet.
			var before = st.executeQuery(
					"select count(*) from information_schema.columns where table_name = 'api_keys' and column_name = 'agent_id'");
			before.next();
			assertThat(before.getInt(1)).as("column absent before V13").isZero();
			for (String name : existing) {
				st.execute("insert into api_keys (name, key_hash, key_prefix, roles, namespace) values ('" + name
						+ "', md5('" + name + "') || md5('x" + name + "'), 'osk_abcdefgh', 'ossian-user', 'ops')");
			}
		}

		var result = flyway(url, "latest").migrate();

		assertThat(result.success).isTrue();
		assertThat(result.migrationsExecuted).isEqualTo(1);
		assertThat(result.migrations.get(0).version).isEqualTo("13");
		try (Connection c = open(url); var st = c.createStatement()) {
			var rows = st.executeQuery("select name, namespace, roles, agent_id from api_keys order by name");
			int seen = 0;
			while (rows.next()) {
				seen++;
				assertThat(existing).contains(rows.getString("name"));
				assertThat(rows.getString("namespace")).as("other columns untouched").isEqualTo("ops");
				assertThat(rows.getString("roles")).isEqualTo("ossian-user");
				assertThat(rows.getString("agent_id")).as("existing keys are unbound").isNull();
			}
			assertThat(seen).isEqualTo(existing.size());
		}
	}

	@Test
	@DisplayName("the database itself refuses a blank binding, and accepts both a real one and none")
	void constraintRejectsBlank() throws Exception {
		String url = freshDatabase();
		flyway(url, "latest").migrate();

		try (Connection c = open(url); var st = c.createStatement()) {
			st.execute("insert into api_keys (name, key_hash, key_prefix, agent_id) values ('a', 'h1', 'osk_1', 'ops-bot')");
			st.execute("insert into api_keys (name, key_hash, key_prefix, agent_id) values ('b', 'h2', 'osk_2', null)");
			assertThatThrownBy(() -> st.execute(
					"insert into api_keys (name, key_hash, key_prefix, agent_id) values ('c', 'h3', 'osk_3', '   ')"))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("ck_api_keys_agent_id_not_blank");
			assertThatThrownBy(() -> st.execute(
					"insert into api_keys (name, key_hash, key_prefix, agent_id) values ('d', 'h4', 'osk_4', repeat('a', 129))"))
				.isInstanceOf(SQLException.class);
		}
	}

	@Test
	@DisplayName("running the migrations again is a no-op")
	void idempotentOnRerun() throws Exception {
		String url = freshDatabase();
		flyway(url, "latest").migrate();

		var again = flyway(url, "latest").migrate();

		assertThat(again.migrationsExecuted).isZero();
		assertThat(flyway(url, "latest").info().current().getVersion().getVersion()).isEqualTo("13");
	}

}
