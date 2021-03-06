package com.vocifery.daytripper.util;

import java.util.HashSet;
import java.util.Set;

public class StringUtils {
	
	private StringUtils() {}
	
	public static Set<String> extractNgrams(String query, int n) {
		Set<String> ngrams = new HashSet<String>();
		String[] words = query.split("\\s+");
		for (int i = 0; i < words.length; i++) {
			int m = n;
			while (m > 0) {
				StringBuilder builder = new StringBuilder();
				for (int j = i; j < i + m; j++) {
					if (j < words.length) {
						builder.append(words[j]);
						builder.append(" ");
					}
				}
				ngrams.add(builder.toString().trim());
				m--;
			}
		}
		return ngrams;
	}
	
	public static String cleanup(String str) {
		return str.replaceAll("_", " ");
	}
}
