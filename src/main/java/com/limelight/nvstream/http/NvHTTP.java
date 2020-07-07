package com.limelight.nvstream.http;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import com.limelight.nvstream.http.PairingManager.PairState;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;


public class NvHTTP {
    private String uniqueId;
    private PairingManager pm;

    public static final int HTTPS_PORT = 47984;
    public static final int HTTP_PORT = 47989;
    public static final int CONNECTION_TIMEOUT = 3000;
    public static final int READ_TIMEOUT = 5000;

    public String baseUrlHttps;
    public String baseUrlHttp;
    
    private OkHttpClient httpClient;
    private OkHttpClient httpClientWithReadTimeout;

    private X509TrustManager trustManager;
    private X509KeyManager keyManager;

    void setServerCert(X509Certificate serverCert) {
    	// Not using cert pinning for OpenGFERelay
    }

    private void initializeHttpState(final LimelightCryptoProvider cryptoProvider) {
        keyManager = new X509KeyManager() {
            public String chooseClientAlias(String[] keyTypes,
                    Principal[] issuers, Socket socket) { return "Limelight-RSA"; }
            public String chooseServerAlias(String keyType, Principal[] issuers,
                    Socket socket) { return null; }
            public X509Certificate[] getCertificateChain(String alias) {
                return new X509Certificate[] {cryptoProvider.getClientCertificate()};
            }
            public String[] getClientAliases(String keyType, Principal[] issuers) { return null; }
            public PrivateKey getPrivateKey(String alias) {
                return cryptoProvider.getClientPrivateKey();
            }
            public String[] getServerAliases(String keyType, Principal[] issuers) { return null; }
        };

        trustManager = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
            public void checkClientTrusted(X509Certificate[] certs, String authType) {
                throw new IllegalStateException("Should never be called");
            }
            public void checkServerTrusted(X509Certificate[] certs, String authType) {
            	// Not validating cert - INSECURE!
            }
        };

        HostnameVerifier hv = new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) {
            	// Not validating hostname - INSECURE!
            	return true;
            }
        };

        httpClient = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                .hostnameVerifier(hv)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
                .build();
        
        httpClientWithReadTimeout = httpClient.newBuilder()
                .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
                .build();
    }
    
    public NvHTTP(String address, LimelightCryptoProvider cryptoProvider) throws IOException {
        // Use the same UID for all Moonlight clients so we can quit games
        // started by other Moonlight clients.
        this.uniqueId = "0123456789ABCDEF";

        initializeHttpState(cryptoProvider);

        try {
            // The URI constructor takes care of escaping IPv6 literals
            this.baseUrlHttps = new URI("https", null, address, HTTPS_PORT, null, null, null).toString();
            this.baseUrlHttp = new URI("http", null, address, HTTP_PORT, null, null, null).toString();
        } catch (URISyntaxException e) {
            // Encapsulate URISyntaxException into IOException for callers to handle more easily
            throw new IOException(e);
        }

        this.pm = new PairingManager(this, cryptoProvider);
    }
    
    String buildUniqueIdUuidString() {
        return "uniqueid="+uniqueId+"&uuid="+UUID.randomUUID();
    }
    
    static String getXmlString(Reader r, String tagname) throws XmlPullParserException, IOException {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser xpp = factory.newPullParser();

        xpp.setInput(r);
        int eventType = xpp.getEventType();
        Stack<String> currentTag = new Stack<String>();
        
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
            case (XmlPullParser.START_TAG):
                if (xpp.getName().equals("root")) {
                    verifyResponseStatus(xpp);
                }
                currentTag.push(xpp.getName());
                break;
            case (XmlPullParser.END_TAG):
                currentTag.pop();
                break;
            case (XmlPullParser.TEXT):
                if (currentTag.peek().equals(tagname)) {
                    return xpp.getText().trim();
                }
                break;
            }
            eventType = xpp.next();
        }

        return null;
    }

    static String getXmlString(String str, String tagname) throws XmlPullParserException, IOException {
        return getXmlString(new StringReader(str), tagname);
    }
    
    private static void verifyResponseStatus(XmlPullParser xpp) throws GfeHttpResponseException {
        // We use Long.parseLong() because in rare cases GFE can send back a status code of
        // 0xFFFFFFFF, which will cause Integer.parseInt() to throw a NumberFormatException due
        // to exceeding Integer.MAX_VALUE. We'll get the desired error code of -1 by just casting
        // the resulting long into an int.
        int statusCode = (int)Long.parseLong(xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_code"));
        if (statusCode != 200) {
            String statusMsg = xpp.getAttributeValue(XmlPullParser.NO_NAMESPACE, "status_message");
            if (statusCode == -1 && "Invalid".equals(statusMsg)) {
                // Special case handling an audio capture error which GFE doesn't
                // provide any useful status message for.
                statusCode = 418;
                statusMsg = "Missing audio capture device. Reinstall GeForce Experience.";
            }
            throw new GfeHttpResponseException(statusCode, statusMsg);
        }
    }
    
    public String getServerInfo() throws IOException, XmlPullParserException {
        String resp;
        
        //
        // TODO: Shield Hub uses HTTP for this and is able to get an accurate PairStatus with HTTP.
        // For some reason, we always see PairStatus is 0 over HTTP and only 1 over HTTPS. It looks
        // like there are extra request headers required to make this stuff work over HTTP.
        //

        try {
            try {
                resp = openHttpConnectionToString(baseUrlHttps + "/serverinfo?"+buildUniqueIdUuidString(), true);
            } catch (SSLHandshakeException e) {
                // Detect if we failed due to a server cert mismatch
                if (e.getCause() instanceof CertificateException) {
                    // Jump to the GfeHttpResponseException exception handler to retry
                    // over HTTP which will allow us to pair again to update the cert
                    throw new GfeHttpResponseException(401, "Server certificate mismatch");
                }
                else {
                    throw e;
                }
            }

            // This will throw an exception if the request came back with a failure status.
            // We want this because it will throw us into the HTTP case if the client is unpaired.
            getServerVersion(resp);
        }
        catch (GfeHttpResponseException e) {
            if (e.getErrorCode() == 401) {
                // Cert validation error - fall back to HTTP
                return openHttpConnectionToString(baseUrlHttp + "/serverinfo", true);
            }

            // If it's not a cert validation error, throw it
            throw e;
        }

        return resp;
    }

    // This hack is Android-specific but we do it on all platforms
    // because it doesn't really matter
    private OkHttpClient performAndroidTlsHack(OkHttpClient client) {
        // Doing this each time we create a socket is required
        // to avoid the SSLv3 fallback that causes connection failures
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(new KeyManager[] { keyManager }, new TrustManager[] { trustManager }, new SecureRandom());
            return client.newBuilder().sslSocketFactory(sc.getSocketFactory(), trustManager).build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException(e);
        }
    }

    // Read timeout should be enabled for any HTTP query that requires no outside action
    // on the GFE server. Examples of queries that DO require outside action are launch, resume, and quit.
    // The initial pair query does require outside action (user entering a PIN) but subsequent pairing
    // queries do not.
    ResponseBody openHttpConnection(String url, boolean enableReadTimeout) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        Response response;

        if (enableReadTimeout) {
            response = performAndroidTlsHack(httpClientWithReadTimeout).newCall(request).execute();
        }
        else {
            response = performAndroidTlsHack(httpClient).newCall(request).execute();
        }

        ResponseBody body = response.body();
        
        if (response.isSuccessful()) {
            return body;
        }
        
        // Unsuccessful, so close the response body
        if (body != null) {
            body.close();
        }
        
        if (response.code() == 404) {
            throw new FileNotFoundException(url);
        }
        else {
            throw new GfeHttpResponseException(response.code(), response.message());
        }
    }
    
    String openHttpConnectionToString(String url, boolean enableReadTimeout) throws IOException {
        ResponseBody resp = openHttpConnection(url, enableReadTimeout);
        String respString = resp.string();
        resp.close();
        return respString;
    }

    public String getServerVersion(String serverInfo) throws XmlPullParserException, IOException {
        return getXmlString(serverInfo, "appversion");
    }

    public PairingManager.PairState getPairState() throws IOException, XmlPullParserException {
        return getPairState(getServerInfo());
    }

    public PairingManager.PairState getPairState(String serverInfo) throws IOException, XmlPullParserException {
        if (!NvHTTP.getXmlString(serverInfo, "PairStatus").equals("1")) {
            return PairState.NOT_PAIRED;
        }

        return PairState.PAIRED;
    }

    public PairingManager getPairingManager() {
        return pm;
    }
    
    public int getServerMajorVersion(String serverInfo) throws XmlPullParserException, IOException {
        return getServerAppVersionQuad(serverInfo)[0];
    }
    
    public int[] getServerAppVersionQuad(String serverInfo) throws XmlPullParserException, IOException {
        String serverVersion = getServerVersion(serverInfo);
        if (serverVersion == null) {
            throw new RuntimeException("Missing server version field");
        }
        String[] serverVersionSplit = serverVersion.split("\\.");
        if (serverVersionSplit.length != 4) {
        	throw new RuntimeException("Malformed server version field: "+serverVersion);
        }
        int[] ret = new int[serverVersionSplit.length];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = Integer.parseInt(serverVersionSplit[i]);
        }
        return ret;
    }
}
