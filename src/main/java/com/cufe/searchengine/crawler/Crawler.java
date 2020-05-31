package com.cufe.searchengine.crawler;

import com.cufe.searchengine.db.DBInitializer;
import com.cufe.searchengine.util.DocumentFilterer;
import com.cufe.searchengine.util.Patterns;
import com.cufe.searchengine.util.StringUtils;
import com.cufe.searchengine.db.table.OutgoingURLsTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

@Component
public class Crawler implements Runnable {
	private static final Logger log = LoggerFactory.getLogger(Crawler.class);

	@Value("${crawler.userAgent}")
	private String userAgent;
	@Autowired
	private UrlsStore urlsStore;
	@Autowired
	private DocumentsStore documentsStore;
	@Autowired
	private OutgoingURLsTable outgoingURLsTable;

	@Override
	public void run() {
		log.info("started");

		while (!Thread.interrupted()) {
			String url = null;
			try {
				url = urlsStore.poll();
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}

			String document;

			try {
				document = loadURL(url);
				if (!StringUtils.isHtml(document)) {
					log.warn("doc at url {} is probably not html, ignore it", url);
					continue;
				}
			} catch (Exception e) {
				log.error("url=" + url + ",error=" + e.getMessage());
				continue;
			}

			String content = DocumentFilterer.textFromHtml(document);
			String pubDate = Patterns.extractHTMLPubDate(content);

			if (document.length() == 0) {
				continue;
			}

			String[] urls = Patterns.extractUrls(document, url);
			log.info("extracted " + urls.length + " urls from " + url + ", document.length=" + document.length());
			for (String u : urls) {
				try {
					urlsStore.add(u);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
				try {
					outgoingURLsTable.insertLink(url, u);
				} catch (Exception e) {
					log.error("can't add url link [" + e.getMessage() + "]");
				}
			}

			String[] images = Patterns.extractImages(document, url);
			log.info("extracted " + images.length + " images from " + url + ", document.length=" + document.length());
			for (String u : images) {
				try {
					documentsStore.add(u, content, pubDate, true);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}

				try {
					outgoingURLsTable.insertLink(url, u);
				} catch (Exception e) {
					log.error("can't add url link [" + e.getMessage() + "]");
				}
			}

			try {
				documentsStore.add(url, content, pubDate, false);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		log.error("interrupted");
	}

	private String loadURL(String link) throws IOException {
		InputStream inputStream = null;
		String result;

		URL url = new URL(link);
		URLConnection urlConnection = url.openConnection();
		urlConnection.setRequestProperty("User-Agent", userAgent);

		try {
			inputStream = urlConnection.getInputStream();
			result = StringUtils.streamToString(inputStream);
		} finally {
			if (inputStream != null) {
				inputStream.close();
			}
		}

		return result;
	}

	@Component
	private static class CrawlersRunner {
		private static final Logger log = LoggerFactory.getLogger(CrawlersRunner.class);

		@Value("${crawler.numThreads}")
		private int numThreads;
		@Autowired
		private Crawler crawler;

		@EventListener
		public void onDBInitialized(DBInitializer.DBInitializedEvent event) {
			log.info("creating " + numThreads + " of threads of crawler");

			for (int i = 0; i < numThreads; i++) {
				new Thread(crawler, String.valueOf(i)).start();
			}

			log.info("created " + numThreads + " threads");
		}
	}
}
