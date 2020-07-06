package com.limelight.nvstream.http;

import java.io.IOException;
import java.net.MalformedURLException;

import okhttp3.ResponseBody;

public class HttpsHelper {
	// This is nasty but it allows us to use a bunch of logic already contained in NvHTTP
	public static String openHttpConnectionToString(NvHTTP http, String url) throws MalformedURLException, IOException {
		url = http.baseUrlHttps + url;
		
		System.out.println("Requesting URL (text): "+url);
		return http.openHttpConnectionToString(url, false);
	}
	
	public static byte[] openHttpConnectionToBytes(NvHTTP http, String url) throws MalformedURLException, IOException {
		url = http.baseUrlHttps + url;
		
		System.out.println("Requesting URL (binary): "+url);
		ResponseBody body = http.openHttpConnection(url, false);
		
		byte[] bytes = body.bytes();
		
		body.close();
		
		return bytes;
	}
}
