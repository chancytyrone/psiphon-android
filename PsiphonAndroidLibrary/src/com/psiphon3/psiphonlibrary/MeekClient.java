/*
 * Copyright (c) 2014, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

/*
 * Ported from meek Go client:
 * https://gitweb.torproject.org/pluggable-transports/meek.git/blob/HEAD:/meek-client/meek-client.go
 * 
 * Notable changes from the Go version, as required for Psiphon Android:
 * - uses VpnService protectSocket(), via Tun2Socks.IProtectSocket and ProtectedPlainSocketFactory, to
 *   exclude connections to the meek relay from the VPN interface
 * - in-process logging
 * - there's no SOCKS interface; the target Psiphon server is fixed when the meek client is constructed
 *   we're making multiple meek clients anyway -- one per target server -- in order to test connections
 *   to different meek relays or via different fronts
 * - unfronted mode, which is HTTP only (with obfuscated cookies used to pass params from client to relay)
 * - initial meek server poll is made with no delay in order to time connection responsiveness
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPostHC4;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.ByteArrayEntityHC4;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.impl.conn.DefaultSchemePortResolver;
import org.apache.http.impl.conn.ManagedHttpClientConnectionFactory;
import org.apache.http.util.EntityUtilsHC4;
import org.json.JSONException;
import org.json.JSONObject;

import android.os.SystemClock;
import ch.ethz.ssh2.crypto.ObfuscatedSSH;

import com.psiphon3.psiphonlibrary.ServerInterface.ProtectedPlainConnectionSocketFactory;
import com.psiphon3.psiphonlibrary.ServerInterface.ProtectedSSLConnectionSocketFactory;
import com.psiphon3.psiphonlibrary.Utils.MyLog;
import com.psiphon3.psiphonlibrary.Utils.RequestTimeoutAbort;

public class MeekClient {

    final static int MEEK_PROTOCOL_VERSION = 1;
    final static int MAX_PAYLOAD_LENGTH = 0x10000;
    final static int MIN_POLL_INTERVAL_MILLISECONDS = 1;
    final static int IDLE_POLL_INTERVAL_MILLISECONDS = 100;
    final static int MAX_POLL_INTERVAL_MILLISECONDS = 5000;
    final static double POLL_INTERVAL_MULTIPLIER = 1.5;
    final static int MEEK_SERVER_TIMEOUT_MILLISECONDS = 20000;
    final static int ABORT_POLL_MILLISECONDS = 100;

    final static String HTTP_POST_CONTENT_TYPE = "application/octet-stream";
    
    final private MeekProtocol mProtocol;
    final private Tun2Socks.IProtectSocket mProtectSocket;
    final private ServerInterface mServerInterface;
    final private String mPsiphonClientSessionId;
    final private String mPsiphonServerAddress;
    final private String mFrontingDomain;
    final private String mFrontingHost;
    final private String mMeekServerHost;
    final private int mMeekServerPort;
    final private String mCookieEncryptionPublicKey;
    final private String mObfuscationKeyword;
    private Thread mAcceptThread;
    private ServerSocket mServerSocket;
    private int mLocalPort = -1;
    private Set<Socket> mClients;
    
    public enum MeekProtocol {FRONTED, UNFRONTED};

    public interface IAbortIndicator {
        public boolean shouldAbort();
    }
    
    public MeekClient(
            Tun2Socks.IProtectSocket protectSocket,
            ServerInterface serverInterface,
            String psiphonClientSessionId,
            String psiphonServerAddress,
            String cookieEncryptionPublicKey,
            String obfuscationKeyword,
            String frontingDomain,
            String frontingHost) {
        mProtocol = MeekProtocol.FRONTED;
        mProtectSocket = protectSocket;
        mServerInterface = serverInterface;
        mPsiphonClientSessionId = psiphonClientSessionId;
        mPsiphonServerAddress = psiphonServerAddress;
        mCookieEncryptionPublicKey = cookieEncryptionPublicKey;
        mObfuscationKeyword = obfuscationKeyword;
        mFrontingDomain = frontingDomain;
        mFrontingHost = frontingHost;
        mMeekServerHost = null;
        mMeekServerPort = -1;
    }

    public MeekClient(
            Tun2Socks.IProtectSocket protectSocket,
            ServerInterface serverInterface,
            String psiphonClientSessionId,
            String psiphonServerAddress,
            String cookieEncryptionPublicKey,
            String obfuscationKeyword,
            String meekServerHost,
            int meekServerPort) {
        mProtocol = MeekProtocol.UNFRONTED;
        mProtectSocket = protectSocket;
        mServerInterface = serverInterface;
        mPsiphonClientSessionId = psiphonClientSessionId;
        mPsiphonServerAddress = psiphonServerAddress;
        mCookieEncryptionPublicKey = cookieEncryptionPublicKey;
        mObfuscationKeyword = obfuscationKeyword;
        mFrontingDomain = null;
        mFrontingHost = null;
        mMeekServerHost = meekServerHost;
        mMeekServerPort = meekServerPort;
    }
    
    public MeekProtocol getProtocol() {
        return mProtocol;
    }

    public synchronized void start() throws IOException {
        stop();
        mServerSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        mLocalPort = mServerSocket.getLocalPort();
        mClients = new HashSet<Socket>();
        mAcceptThread = new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            while(true) {
                                final Socket finalSocket = mServerSocket.accept();
                                registerClient(finalSocket);
                                Thread clientThread = new Thread(
                                        new Runnable() {
                                           @Override
                                           public void run() {
                                               try {
                                                   runClient(finalSocket);
                                               } finally {
                                                   unregisterClient(finalSocket);
                                               }
                                           }
                                        });
                                clientThread.start();
                            }
                        } catch (NullPointerException e) {
                            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                        } catch (SocketException e) {
                            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                        } catch (IOException e) {
                            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                        }                        
                    }
                });
        mAcceptThread.start();
    }
    
    public synchronized int getLocalPort() {
        return mLocalPort;
    }
    
    public synchronized void stop() {
        if (mServerSocket != null) {
            Utils.closeHelper(mServerSocket);
            try {
                mAcceptThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            stopClients();
            mServerSocket = null;
            mAcceptThread = null;
            mLocalPort = -1;
        }
    }
    
    private synchronized void registerClient(Socket socket) {
        mClients.add(socket);
    }
    
    private synchronized void unregisterClient(Socket socket) {        
        mClients.remove(socket);
    }
    
    private synchronized void stopClients() {
        // Note: not actually joining client threads
        for (Socket socket : mClients) {
            Utils.closeHelper(socket);
        }
        mClients.clear();
    }
    
    private void runClient(Socket socket) {
        InputStream socketInputStream = null;
        OutputStream socketOutputStream = null;
        HttpClientConnectionManager connManager = null;
        CloseableHttpClient httpClient = null;
        try {
            socketInputStream = socket.getInputStream();
            socketOutputStream = socket.getOutputStream();
            String cookie = makeCookie();
            byte[] payloadBuffer = new byte[MAX_PAYLOAD_LENGTH];
            int pollIntervalMilliseconds = MIN_POLL_INTERVAL_MILLISECONDS;
            
            RegistryBuilder<ConnectionSocketFactory> registryBuilder = RegistryBuilder.<ConnectionSocketFactory> create();
            
            if (mFrontingDomain != null) {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null,  null,  null);
                ProtectedSSLConnectionSocketFactory sslSocketFactory = new ProtectedSSLConnectionSocketFactory(
                        mProtectSocket, sslContext, SSLSocketFactory.BROWSER_COMPATIBLE_HOSTNAME_VERIFIER);
                registryBuilder.register("https",  sslSocketFactory);                
            } else {
                ProtectedPlainConnectionSocketFactory plainSocketFactory = new ProtectedPlainConnectionSocketFactory(mProtectSocket);
                registryBuilder.register("http",  plainSocketFactory); 
            }
            
            Registry<ConnectionSocketFactory> socketFactoryRegistry = registryBuilder.build();

            // Use ProtectedDnsResolver to resolve the fronting domain outside of the tunnel
            DnsResolver dnsResolver = ServerInterface.getDnsResolver(mProtectSocket, mServerInterface);
            connManager = new BasicHttpClientConnectionManager(socketFactoryRegistry,
                    ManagedHttpClientConnectionFactory.INSTANCE, DefaultSchemePortResolver.INSTANCE , dnsResolver);

            RequestConfig.Builder requestBuilder = RequestConfig.custom()
                    .setConnectTimeout(MEEK_SERVER_TIMEOUT_MILLISECONDS)
                    .setConnectionRequestTimeout(MEEK_SERVER_TIMEOUT_MILLISECONDS)
                    .setSocketTimeout(MEEK_SERVER_TIMEOUT_MILLISECONDS);
            
            httpClient = HttpClientBuilder
                    .create()
                    .setConnectionManager(connManager)
                    .disableCookieManagement()
                    .disableAutomaticRetries()
                    .build();
            
            URI uri = null;
            if (mFrontingDomain != null) {
                uri = new URIBuilder().setScheme("https").setHost(mFrontingDomain).setPath("/").build();
            } else {
                uri = new URIBuilder().setScheme("http").setHost(mMeekServerHost).setPort(mMeekServerPort).setPath("/").build();                    
            }

            long lastSuccessfulMeekRequest = 0;
            
            while (true) {
                // TODO: read in a separate thread (or asynchronously) to allow continuous requests while streaming downloads
                socket.setSoTimeout(pollIntervalMilliseconds);
                int payloadLength = 0;
                long timeBeforeLocalRead = SystemClock.elapsedRealtime();
                try {
                    payloadLength = socketInputStream.read(payloadBuffer);
                } catch (SocketTimeoutException e) {
                    // If this read took longer to timeout than specified, the device is probably sleeping.
                    // See https://code.google.com/p/android/issues/detail?id=2782
                    long timeAfterLocalRead = SystemClock.elapsedRealtime();
                    long readDuration = timeAfterLocalRead - timeBeforeLocalRead;
                    if (readDuration > pollIntervalMilliseconds + 1000) {
                        MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE,
                                String.format("socket.read() duration: %d ms", readDuration));
                        // This is a temporary hack to avoid constantly reconnecting while the device is sleeping.
                        // This basically idles the meek client until read starts behaving properly.
                        if (lastSuccessfulMeekRequest > 0 &&
                                (timeAfterLocalRead - lastSuccessfulMeekRequest) > 2 * MEEK_SERVER_TIMEOUT_MILLISECONDS) {
                            continue;
                        }
                    }
                    
                    // Otherwise in this case, we POST with no content -- this is for polling the server
                }
                if (payloadLength == -1) {
                    // EOF
                    break;
                }

                // (comment from meek-client.go)
                // Retry loop, which assumes entire request failed (underlying
                // transport protocol such as SSH will fail if extra bytes are
                // replayed in either direction due to partial request success
                // followed by retry).
                // This retry mitigates intermittent failures between the client
                // and front/server.
                int retry;
                for (retry = 1; retry >= 0; retry--) {
                    HttpPostHC4 httpPost = new HttpPostHC4(uri);
                    ByteArrayEntityHC4 entity = new ByteArrayEntityHC4(payloadBuffer, 0, payloadLength);
                    entity.setContentType(HTTP_POST_CONTENT_TYPE);
                    httpPost.setEntity(entity);
                    httpPost.setConfig(requestBuilder.build());

                    if (mFrontingDomain != null) {
                        httpPost.addHeader("Host", mFrontingHost);
                    }
                    httpPost.addHeader("Cookie", cookie);
                    
                    CloseableHttpResponse response = null;
                    HttpEntity rentity = null;
                    
                    try {
                        if (lastSuccessfulMeekRequest > 0 &&
                                (SystemClock.elapsedRealtime() - lastSuccessfulMeekRequest) > 2 * MEEK_SERVER_TIMEOUT_MILLISECONDS) {
                            // If too much time has elapsed between successful meek requests (ie the device has been in deep sleep),
                            // the server will expire the meek session. In that case we will continue to get 200 OK responses
                            // from the server, with empty response payloads. The tunneled connection may remain in a connected
                            // state but no data will be transferred.
                            // Instead, we will abort this meek client session.
                            return;
                        }
                        
                        RequestTimeoutAbort timeoutAbort = new RequestTimeoutAbort(httpPost);
                        new Timer(true).schedule(timeoutAbort, MEEK_SERVER_TIMEOUT_MILLISECONDS);
                        try {
                            response = httpClient.execute(httpPost);
                        } catch (IOException e) {
                            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                            // Retry (or abort)
                            continue;
                        } finally {
                            timeoutAbort.cancel();
                        }
                        int statusCode = response.getStatusLine().getStatusCode();
                        if (statusCode != HttpStatus.SC_OK) {
                            MyLog.w(R.string.meek_http_request_error, MyLog.Sensitivity.NOT_SENSITIVE, statusCode);
                            // Retry (or abort)
                            continue;
                        }
                        
                        lastSuccessfulMeekRequest = SystemClock.elapsedRealtime();
    
                        boolean receivedData = false;
                        rentity = response.getEntity();
                        if (rentity != null) {
                            InputStream responseInputStream = rentity.getContent();
                            try {
                                int readLength;
                                while ((readLength = responseInputStream.read(payloadBuffer)) != -1) {
                                    receivedData = true;
                                    socketOutputStream.write(payloadBuffer, 0 , readLength);
                                }
                            } finally {
                                Utils.closeHelper(responseInputStream);
                            }
                        }
                        
                        if (payloadLength > 0 || receivedData) {
                            pollIntervalMilliseconds = MIN_POLL_INTERVAL_MILLISECONDS;
                        } else if (pollIntervalMilliseconds == MIN_POLL_INTERVAL_MILLISECONDS) {
                            pollIntervalMilliseconds = IDLE_POLL_INTERVAL_MILLISECONDS;
                        } else {
                            pollIntervalMilliseconds = (int)(pollIntervalMilliseconds*POLL_INTERVAL_MULTIPLIER);
                        }
                        if (pollIntervalMilliseconds > MAX_POLL_INTERVAL_MILLISECONDS) {
                            pollIntervalMilliseconds = MAX_POLL_INTERVAL_MILLISECONDS;
                        }
                    } finally {
                        if (rentity == null && response != null) {
                            rentity = response.getEntity();
                        }
                        if (rentity != null) {
                            EntityUtilsHC4.consume(rentity);
                        }
                        Utils.closeHelper(response);

                        httpPost.releaseConnection();
                    }
                    // Success: exit retry loop
                    break;
                }
                if (retry < 0) {
                    // All retries failed, so abort this meek client session
                    break;
                }
            }
        } catch (ClientProtocolException e) {
        	MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (IOException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (URISyntaxException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (UnsupportedOperationException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (IllegalStateException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (IllegalArgumentException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (NullPointerException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());                    
        } catch (KeyManagementException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());                    
        } catch (JSONException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());                    
        } catch (GeneralSecurityException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());                    
        } finally {
            Utils.closeHelper(httpClient);
            Utils.closeHelper(socketInputStream);
            Utils.closeHelper(socketOutputStream);
            Utils.closeHelper(socket);
        }
    }
    
    private String makeCookie()
            throws JSONException, GeneralSecurityException, IOException {

        JSONObject payload = new JSONObject();
        payload.put("v", MEEK_PROTOCOL_VERSION);
        payload.put("s", mPsiphonClientSessionId);
        payload.put("p", mPsiphonServerAddress);

        // NaCl crypto_box: http://nacl.cr.yp.to/box.html
        // The recipient public key is known and trusted (embedded in the signed APK)
        // The sender public key is ephemeral (recipient does not authenticate sender)
        // The nonce is fixed as as 0s; the one-time, single-use ephemeral public key is sent with the box
        
        org.abstractj.kalium.keys.PublicKey recipientPublicKey = new org.abstractj.kalium.keys.PublicKey(
                Utils.Base64.decode(mCookieEncryptionPublicKey));
        org.abstractj.kalium.keys.KeyPair ephemeralKeyPair = new org.abstractj.kalium.keys.KeyPair();
        byte[] nonce = new byte[org.abstractj.kalium.SodiumConstants.NONCE_BYTES]; // Java bytes arrays default to 0s
        org.abstractj.kalium.crypto.Box box = new org.abstractj.kalium.crypto.Box(recipientPublicKey, ephemeralKeyPair.getPrivateKey());
        byte[] message = box.encrypt(nonce, payload.toString().getBytes("UTF-8"));
        byte[] ephemeralPublicKeyBytes = ephemeralKeyPair.getPublicKey().toBytes();
        byte[] encryptedPayload = new byte[ephemeralPublicKeyBytes.length + message.length];
        System.arraycopy(ephemeralPublicKeyBytes, 0, encryptedPayload, 0, ephemeralPublicKeyBytes.length);
        System.arraycopy(message, 0, encryptedPayload, ephemeralPublicKeyBytes.length, message.length);

        String cookieValue;
        if (mObfuscationKeyword != null) {
            final int OBFUSCATE_MAX_PADDING = 32;
            ObfuscatedSSH obfuscator = new ObfuscatedSSH(mObfuscationKeyword, OBFUSCATE_MAX_PADDING);
            byte[] obfuscatedSeedMessage = obfuscator.getSeedMessage();
            byte[] obfuscatedPayload = new byte[encryptedPayload.length];
            System.arraycopy(encryptedPayload, 0, obfuscatedPayload, 0, encryptedPayload.length);
            obfuscator.obfuscateOutput(obfuscatedPayload);
            byte[] obfuscatedCookieValue = new byte[obfuscatedSeedMessage.length + obfuscatedPayload.length];
            System.arraycopy(obfuscatedSeedMessage, 0, obfuscatedCookieValue, 0, obfuscatedSeedMessage.length);
            System.arraycopy(obfuscatedPayload, 0, obfuscatedCookieValue, obfuscatedSeedMessage.length, obfuscatedPayload.length);
            cookieValue = Utils.Base64.encode(obfuscatedCookieValue);
        } else {
            cookieValue = Utils.Base64.encode(encryptedPayload);
        }

        // Select a random-ish cookie key (which will be observable and subject to fingerprinting in unfronted mode)
        final String cookieKeyValues = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        char cookieKey = cookieKeyValues.toCharArray()[Utils.insecureRandRange(0, cookieKeyValues.length()-1)];

        return cookieKey + "=" + cookieValue;
    }
}
