package opengferelay.https;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import org.veryquick.embweb.EmbeddedServer;
import org.veryquick.embweb.HttpRequestHandler;
import org.veryquick.embweb.Response;

import com.limelight.nvstream.http.HttpsHelper;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvHTTP;

public class HttpsRelay {
	private static final GfeKeyProvider keyProvider = new GfeKeyProvider();
	
	private final NvHTTP httpObj;
	private final String reportedLocalAddress, reportedRemoteAddress;
	
	public HttpsRelay(String reportedLocalAddress, String reportedRemoteAddress, 
			InetAddress remoteAddress, LimelightCryptoProvider cryptoProv) throws IOException {
		this.reportedLocalAddress = reportedLocalAddress;
		this.reportedRemoteAddress = reportedRemoteAddress;
		this.httpObj = new NvHTTP(remoteAddress.getHostAddress(), cryptoProv);
		
		// Force the key pair to be loaded now to ensure it successfully loads
		keyProvider.getServerCertificateChain();
	}
	
	private SSLContext createSslContext() throws NoSuchAlgorithmException, KeyManagementException {
		// Create a trust manager that does not validate certificate chains
		TrustManager[] trustAllCerts = new TrustManager[] { 
				new X509TrustManager() {
					public X509Certificate[] getAcceptedIssuers() {
						return new X509Certificate[0]; 
					}
					public void checkClientTrusted(X509Certificate[] certs, String authType) {}
					public void checkServerTrusted(X509Certificate[] certs, String authType) {}
				}};

		KeyManager[] ourKeyman = new KeyManager[] {
				new X509KeyManager() {
					public String chooseClientAlias(String[] keyTypes,
							Principal[] issuers, Socket socket) {
						return null;
					}

					public String chooseServerAlias(String keyType, Principal[] issuers,
							Socket socket) {
						return "GFE-RSA";
					}

					public X509Certificate[] getCertificateChain(String alias) {
						return keyProvider.getServerCertificateChain();
					}

					public String[] getClientAliases(String keyType, Principal[] issuers) {
						return null;
					}

					public PrivateKey getPrivateKey(String alias) {
						return keyProvider.getServerPrivateKey();
					}

					public String[] getServerAliases(String keyType, Principal[] issuers) {
						return null;
					}
				}
		};

		SSLContext sc = SSLContext.getInstance("TLS");
		sc.init(ourKeyman, trustAllCerts, new SecureRandom());
		return sc;
	}
	
	private String constructFullUrlString(String url, Map<String, String> parameters) {
		String str = url;
		
		boolean first = true;
		for (Map.Entry<String, String> entry : parameters.entrySet()) {
			if (first) {
				str += "?";
				first = false;
			}
			else {
				str += "&";
			}
			str += entry.getKey() + "=" + entry.getValue();
		}
		
		return str;
	}
	
	private static String removeXmlElement(String tagName, String xmlData) {
		if (!xmlData.contains(tagName)) {
			// Tag wasn't there to begin with
			System.out.println("Tag not found: "+tagName);
			return xmlData;
		}
		
		String startTag = "<"+tagName+">";
		String endTag = "</"+tagName+">";
		
		String pre = xmlData.substring(0, xmlData.indexOf(startTag));
		String post = xmlData.substring(xmlData.indexOf(endTag) + endTag.length(), xmlData.length());
		
		xmlData = pre + post;
		
		return xmlData;
	}
	
	private static String patchXmlElement(String tagName, String newValue, String xmlData) {
		if (!xmlData.contains(tagName)) {
			// Tag wasn't there to begin with
			System.out.println("Tag not found: "+tagName);
			return xmlData;
		}
		
		String startTag = "<"+tagName+">";
		String endTag = "</"+tagName+">";
		
		String pre = xmlData.substring(0, xmlData.indexOf(startTag));
		String post = xmlData.substring(xmlData.indexOf(endTag) + endTag.length(), xmlData.length());
		
		xmlData = pre + startTag + newValue + endTag + post;
		
		return xmlData;
	}
	
	private static String getXmlElement(String tagName, String xmlData) {
		if (!xmlData.contains(tagName)) {
			// Tag wasn't there to begin with
			System.out.println("Tag not found: "+tagName);
			return null;
		}
		
		String startTag = "<"+tagName+">";
		String endTag = "</"+tagName+">";
		
		return xmlData.substring(xmlData.indexOf(startTag) + startTag.length(), xmlData.indexOf(endTag));
	}
	
	public void start(int serverPort, boolean https) throws Exception {
		EmbeddedServer.createInstance(serverPort,
				https ? createSslContext() : null,
				new HttpRequestHandler() {
					@Override
					public Response handleRequest(Type type, String url,
							Map<String, String> parameters) {
						String fullUrl = constructFullUrlString(url, parameters);
						System.out.println("Got request: "+fullUrl);
						
						Response resp = new Response();
						
						// Check if special handling is needed for this request
						if (fullUrl.startsWith("/serverinfo")) {
							resp.setContentType("text/plain");

							try {
								String serverInfoResp = HttpsHelper.openHttpConnectionToString(httpObj, fullUrl);
								
								// Patch IP addresses to desired values
								serverInfoResp = patchXmlElement("LocalIP", reportedLocalAddress, serverInfoResp);
								serverInfoResp = patchXmlElement("ExternalIP", reportedRemoteAddress, serverInfoResp);
								
								// Change the host name to add an extension to the name
								String hostname = getXmlElement("hostname", serverInfoResp);
								hostname += " (Open)";
								serverInfoResp = patchXmlElement("hostname", hostname, serverInfoResp);
								
								// Change the unique ID so it appears as a different device
								String uniqueId = getXmlElement("uniqueid", serverInfoResp);
								if (Character.isDigit(uniqueId.charAt(0))) {
									uniqueId = "a" + uniqueId.substring(1);
								}
								else {
									uniqueId = "0" + uniqueId.substring(1);
								}
								serverInfoResp = patchXmlElement("uniqueid", uniqueId, serverInfoResp);
								
								System.out.println("Response from server: "+serverInfoResp);

								resp.addContent(serverInfoResp);
								resp.setOk();
							} catch (Exception e) {
								e.printStackTrace();
								resp.setError(e);
							}
						}
						else if (fullUrl.startsWith("/appasset")) {
							resp.setContentType("image/png");
							
							try {
								byte[] asset = HttpsHelper.openHttpConnectionToBytes(httpObj, fullUrl);
								System.out.println("PNG asset received");
								resp.setBinaryContent(asset);
								resp.setOk();
							} catch (Exception e) {
								e.printStackTrace();
								resp.setError(e);
							}
						}
						else {
							resp.setContentType("text/plain");
							
							// Just pass the request on
							try {
								String content = HttpsHelper.openHttpConnectionToString(httpObj, fullUrl);
								System.out.println("Response from server (passing unmodified): "+content);
								resp.addContent(content);
								resp.setOk();
							} catch (Exception e) {
								e.printStackTrace();
								resp.setError(e);
							}
						}
						
						return resp;
					}
		});
		if (https) {
			System.out.println("HTTPS server listening on "+serverPort);
		}
		else {
			System.out.println("HTTP server listening on "+serverPort);
		}
	}
}