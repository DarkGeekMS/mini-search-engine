package com.cufe.searchengine.util;

import java.text.MessageFormat;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Patterns {
	private static final Pattern HTML_TITLE = Pattern.compile("<title>(.+)</title>",
		Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE);
	private static final Pattern HTML_PUBDATE = Pattern.compile("\"datePublished\":\"(.+)T",
		Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE);
	private static final Pattern URL_PATTERN = Pattern.compile("(((https?|ftp):)//)(\\S+(:\\S*)?@)?((?!(10|127)(\\.\\d{1,3}){3})(?!(169\\.254|192\\.168)(\\.\\d{1,3}){2})(?!172\\.(1[6-9]|2\\d|3[0-1])(\\.\\d{1,3}){2})([1-9]\\d?|1\\d\\d|2[01]\\d|22[0-3])(\\.(1?\\d{1,2}|2[0-4]\\d|25[0-5])){2}(\\.([1-9]\\d?|1\\d\\d|2[0-4]\\d|25[0-4]))|(([a-z0-9\\u00a1-\\uffff][a-z0-9\\u00a1-\\uffff_-]{0,62})?[a-z0-9\\u00a1-\\uffff]\\.)+([a-z\\u00a1-\\uffff]{2,}\\.?))(:\\d{2,5})?([/?#]\\S*)?", Pattern.CASE_INSENSITIVE);
	private static final Pattern HREF_PATTERN = Pattern.compile("href *= *\"([^\"]+)\"",
		Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);
	private static final Pattern IMG_PATTERN = Pattern.compile("img *src *= *\"([^\"]+)\"",
			Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);

	public static String extractWebsite(String url) {
		List<String> matches = URL_PATTERN.matcher(url)
			.results()
			.map(Patterns::websiteStringFromMatch)
			.collect(Collectors.toList());

		return matches.size() == 0 ? "" : matches.get(0);
	}

	private static String getPortString(String port) {
		return port == null ? "" : port.equals(":80") ? "" : port;
	}

	public static String[] extractUrls(String html, String url) {
		String baseUrl = extractWebsite(url);
		baseUrl = baseUrl.equals("")? url:baseUrl;
		final String finalBaseUrl = baseUrl;

		return HREF_PATTERN.matcher(html)
			.results()
			.map(s -> s.group(1))
			.map(s -> {
				if (!s.startsWith("http")) {
					return finalBaseUrl + "/" + s;
				}
				return s;
			})
			.filter(s -> URL_PATTERN.matcher(s).matches())
			.filter(Patterns::couldBeHtml)
			.map(String::trim)
			.distinct()
			.toArray(String[]::new);
	}

	public static String extractHtmlTitle(String html) {
		List<String> collect = HTML_TITLE.matcher(html).results().map(s -> s.group(1)).collect(Collectors.toList());
		return collect.size() > 0 ? collect.get(0) : "";
	}

	public static String extractHTMLPubDate(String html) {
		List<String> collect = HTML_PUBDATE.matcher(html).results().map(s -> s.group(1)).collect(Collectors.toList());
		return collect.size() > 0 ? collect.get(0).substring(0, 10) : "";
	}

	public static boolean couldBeHtml(String url) {
		Matcher matcher = URL_PATTERN.matcher(url);
		if (!matcher.matches()) {
			return false;
		}

		url = matcher.group(22);
		if (url == null || url.equals("")) {
			return true;
		}

		boolean hasFormat = Pattern.compile(".*\\.\\w+$").matcher(url).matches();
		if (!hasFormat) {
			return true;
		}

		// has html file format
		return Pattern.compile(".*\\.(html|asp|htm|php)$").matcher(url).matches();
	}

	public static boolean isImage(String url) {
		Matcher matcher = URL_PATTERN.matcher(url);
		if (!matcher.matches()) {
			return false;
		}

		url = matcher.group(22);
		if (url == null || url.equals("")) {
			return false;
		}

		boolean hasFormat = Pattern.compile(".*\\.\\w+$").matcher(url).matches();
		if (!hasFormat) {
			return false;
		}

		// has some image file format
		return Pattern.compile("/.+\\.(png|jpg|jpeg|gif|tiff|webp)$").matcher(url).matches();
	}

	private static String websiteStringFromMatch(MatchResult s) {
		String port = s.group(21);
		String protocol = s.group(1);
		String site = s.group(18);
		String com = s.group(20);

		return MessageFormat.format("{0}{1}{2}{3}", protocol, site, com, getPortString(port));
	}

	public static String httpToHttps(String url) {
		return url.replaceFirst("http://", "https://");
	}

	public static String[] extractImages(String html, String url) {
		String baseUrl = extractWebsite(url);
		baseUrl = baseUrl.equals("")? url:baseUrl;
		final String finalBaseUrl = baseUrl;

		return IMG_PATTERN.matcher(html)
				.results()
				.map(s -> s.group(1))
				.map(s -> {
					if (!s.startsWith("http")) {
						return finalBaseUrl + "/" + s;
					}
					return s;
				})
				.filter(Patterns::couldBeHtml)
				.map(String::trim)
				.distinct()
				.toArray(String[]::new);
	}
}
