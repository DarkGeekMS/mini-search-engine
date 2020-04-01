package com.cufe.searchengine.crawler;

import com.cufe.searchengine.db.DBInitializer;
import com.cufe.searchengine.util.DBUtils;
import com.cufe.searchengine.util.Patterns;
import com.cufe.searchengine.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.stream.Collectors;

// TODO: better seed set

@Component
public class UrlsStore {
	private static final Logger log = LoggerFactory.getLogger(UrlsStore.class);

	private final BlockingQueue<ComparableUrl> store = new PriorityBlockingQueue<>();
	private final Set<String> allUrls = new HashSet<>();
	private static final Map<String, Long> websiteLastTime = new HashMap<>();

	@Autowired
	private RobotsStore robotsStore;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private DocumentsStore documentsStore;
	@Value("${crawler.urlsStore.saveStateWaitMillis}")
	private long saveStateWaitMillis;
	@Value("${crawler.seedFile}")
	private Resource crawlerSeedResource;
	private int counter = 1;

	@EventListener
	public void onDBInitialized(DBInitializer.DBInitializedEvent event) throws Exception {
		List<String> urls = DBUtils.waitLock(100,
			() -> jdbcTemplate.queryForList("SELECT url FROM urlstore_queue;", String.class)
		);

		if (urls.size() > 0) {
			store.addAll(
				urls.stream()
					.map(ComparableUrl::new)
					.collect(Collectors.toList())
			);

			log.info("urls loaded from db.size() = " + store.size());
		} else {
			store.addAll(
				StringUtils.resourceToLines(crawlerSeedResource)
					.stream()
					.map(ComparableUrl::new)
					.collect(Collectors.toList())
			);

			log.info("loaded seeds of size=" + store.size());
		}

		readCounter();
		loadAllUrls();
		fillWebsiteLastTime();
	}

	private void fillWebsiteLastTime() throws Exception {
		websiteLastTime.clear();

		DBUtils.waitLock(100,
			() -> jdbcTemplate.query(
				"SELECT url, timeMillis FROM documents;", this::mapRowWebSiteLastTime
			)
		);

		log.info("loaded into websiteLastTime {} items", websiteLastTime.size());
	}

	private void readCounter() throws Exception {
		counter = DBUtils.waitLock(100,
			() -> jdbcTemplate.queryForObject("PRAGMA user_version;", Integer.class));
		log.info("loaded counter = {}", counter);
	}

	@EventListener
	public synchronized void onCrawlingFinished(DocumentsStore.CrawlingFinishedEvent event) throws Exception {
		log.info("crawling finished");

		incrementCounter();
		resetStore();
		loadAllUrls();
	}

	@EventListener
	public void onDocumentStoredEvent(DocumentsStore.DocumentStoredEvent event) {
		long time = event.getTimeMillis();
		String website = Patterns.httpToHttps(Patterns.extractWebsite(event.getUrl()));

		websiteLastTime.computeIfPresent(website, (s, time0) -> Math.max(time0, time));
		websiteLastTime.putIfAbsent(website, time);
	}

	private void loadAllUrls() throws Exception {
		allUrls.clear();
		log.info("cleared allUrls");

		allUrls.addAll(queryAllUrlsToAvoid());
		log.info("fetched all urls, size={}", allUrls.size());
	}

	private void resetStore() throws Exception {
		store.clear();
		log.info("cleared store");

		store.addAll(
			queryAllUrlsToReuse().stream()
				.map(ComparableUrl::new)
				.collect(Collectors.toList())
		);
		log.info("added {} urls to store", store.size());
	}

	private void incrementCounter() throws Exception {
		counter++;
		DBUtils.waitLock(100, () -> jdbcTemplate.update("PRAGMA user_version = ?;", counter));
		log.info("incremented counter to {}", counter);
	}

	private List<String> queryAllUrlsToReuse() throws Exception {
		return DBUtils.waitLock(100, () -> jdbcTemplate.query("SELECT url FROM documents WHERE counter != ?;",
			(row, i) -> row
				.getString(1), counter));
	}

	private List<String> queryAllUrlsToAvoid() throws Exception {
		return DBUtils.waitLock(100, () -> jdbcTemplate.query("SELECT url FROM documents WHERE counter = ?;",
			(row, i) -> row
				.getString(1), counter));
	}

	@PreDestroy
	private void onDestroy() throws Exception {
		Object[] storeCopy = store.stream().distinct().filter(Objects::nonNull).toArray();

		// flush
		DBUtils.waitLock(100, () -> {
			jdbcTemplate.execute("DELETE FROM urlstore_queue;");
			return null;
		});

		log.info("flushed urlstore_queue table");

		// commit
		if (storeCopy.length == 0) {
			log.info("empty state, no saving");
			return;
		}
		log.info("save my state before closing");

		StringBuilder sql = new StringBuilder("INSERT INTO urlstore_queue VALUES");
		for (int i = 0; i < storeCopy.length - 1; i++) {
			sql.append("(?),");
		}
		sql.append("(?);");
		DBUtils.waitLock(100, () -> jdbcTemplate.update(sql.toString(), storeCopy));

		log.info("saved {} urls", storeCopy.length);
	}

	public void add(String url) throws InterruptedException {
		if (url == null) {
			return;
		}

		ComparableUrl comparableUrl = new ComparableUrl(url);

		// no duplicates
		if (store.contains(comparableUrl) || allUrls.contains(url)) {
			log.warn("duplicate url {}", url);
			return;
		}

		if (!Patterns.couldBeHtml(url)) {
			log.info("url={} is probably not html, ignore it", url);
			return;
		}

		if (!robotsStore.canRequest(url, () -> store.remove(comparableUrl))) {
			return;
		}

		store.put(comparableUrl);
	}

	public String poll() throws InterruptedException {
		String url = store.take().getUrl();
		allUrls.add(url);
		return url;
	}

	public int size() {
		return store.size();
	}

	public int getCounter() {
		return counter;
	}

	private Void mapRowWebSiteLastTime(ResultSet r, int i) throws SQLException {
		long time = r.getLong(2);
		String website = Patterns.httpToHttps(Patterns.extractWebsite(r.getString(1)));

		websiteLastTime.computeIfPresent(website, (s, time0) -> Math.max(time0, time));
		websiteLastTime.putIfAbsent(website, time);

		return null;
	}

	private static class ComparableUrl implements Comparable<ComparableUrl> {
		private final String url;
		private final String normalizedUrl;

		private ComparableUrl(String url) {
			this.url = url;
			this.normalizedUrl = Patterns.httpToHttps(Patterns.extractWebsite(url));
		}

		@Override
		public boolean equals(Object obj) {
			if (obj instanceof ComparableUrl) {
				ComparableUrl comparableUrl = (ComparableUrl) obj;
				return url.equals(comparableUrl.url);
			}

			return false;
		}

		public String getUrl() {
			return url;
		}

		@Override
		public int compareTo(ComparableUrl url2) {
			if (this.equals(url2)) {
				return 0;
			}

			return Math.toIntExact(-(getTime() - url2.getTime()));
		}

		private Long getTime() {
			return websiteLastTime.getOrDefault(normalizedUrl, Long.MAX_VALUE);
		}
	}
}
