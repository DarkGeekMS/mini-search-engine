package com.cufe.searchengine.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.init.UncategorizedScriptException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Objects;

@Component
public class DBInitializer implements CommandLineRunner {
	public static final Logger log = LoggerFactory.getLogger(DBInitializer.class);

	@Value("${db.schemaFile}")
	private Resource sqliteSchemaResource;
	@Value("${db.populateFile}")
	private Resource populateDBResource;
	@Value("${db.populate}")
	private boolean populateDB;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Override
	public void run(String... args) throws SQLException {
		create();

		if (populateDB) {
			populate();
		}
	}

	public void create() throws SQLException {
		log.info("Creating tables");

		try {
			ScriptUtils.executeSqlScript(Objects.requireNonNull(
				jdbcTemplate.getDataSource()).getConnection(), sqliteSchemaResource);
		} catch (UncategorizedScriptException ignored) {
			log.warn("empty sqlite_schema.sql, ignoring it");
		}
	}

	public void populate() throws SQLException {
		log.info("Populating DB with test data");

		try {
			ScriptUtils.executeSqlScript(Objects.requireNonNull(
				jdbcTemplate.getDataSource()).getConnection(), populateDBResource);
		} catch (UncategorizedScriptException ignored) {
			log.warn("empty populate_db.sql, ignoring it");
		}
	}
}