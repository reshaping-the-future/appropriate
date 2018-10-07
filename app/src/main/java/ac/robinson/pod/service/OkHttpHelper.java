package ac.robinson.pod.service;

import android.support.annotation.NonNull;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

import okhttp3.Cache;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;

// see: https://github.com/square/okhttp/issues/2122
public class OkHttpHelper {

	private static OkHttpClient sOkHttpClient;

	// the WiFi adapter advertises that it cannot handle more than 5 simultaneous connections; in reality others
	// could be connected, so stick to three
	private static final int SIMULTANEOUS_CONNECTIONS = 3;

	public static final int SOCKET_TIMEOUT_SHORT = 3;
	public static final int SOCKET_TIMEOUT_DEFAULT = 15;

	// disable caching so we always get from network
	private static final Cache CACHE = null;

	// This should be less than the lowest "normal" upload bandwidth times SOCKET_TIMEOUT_DEFAULT,
	// but not too low or upload speed with long fat networks will suffer.
	// Because it also affects the TCP window size it should preferably be a power of two.
	private static final int SEND_WINDOW_SIZE_BYTES = (int) Math.pow(2, 16); // 64 KiB

	// OkHttp starts waiting for the response as soon as the last byte of the request body is either
	// in the socket buffer or on the wire. This results in a read timeout if the socket buffers
	// or the TCP window are large and the upload bandwidth is small.
	//
	// As a workaround this socket factory sets a smaller socket send buffer, effectively also
	// setting an upper limit for the TCP window size.
	private static class CustomSocketFactory extends SocketFactory {
		private SocketFactory mDefaultSocketFactory;

		CustomSocketFactory(SocketFactory defaultSocketFactory) {
			if (defaultSocketFactory == null) {
				mDefaultSocketFactory = SocketFactory.getDefault();
			} else {
				mDefaultSocketFactory = defaultSocketFactory;
			}
		}

		@Override
		public Socket createSocket(String host, int port) throws IOException {
			return setSocketOptions(mDefaultSocketFactory.createSocket(host, port));
		}

		@Override
		public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
			return setSocketOptions(mDefaultSocketFactory.createSocket(host, port, localHost, localPort));
		}

		@Override
		public Socket createSocket(InetAddress host, int port) throws IOException {
			return setSocketOptions(mDefaultSocketFactory.createSocket(host, port));
		}

		@Override
		public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws
				IOException {
			return setSocketOptions(mDefaultSocketFactory.createSocket(address, port, localAddress, localPort));
		}

		@Override
		public Socket createSocket() throws IOException {
			return setSocketOptions(mDefaultSocketFactory.createSocket());
		}

		private Socket setSocketOptions(Socket socket) throws SocketException {
			socket.setSendBufferSize(SEND_WINDOW_SIZE_BYTES);

			return socket;
		}
	}

	// OkHttp writes the entire request body at once, triggering Okio's timeout mechanism if sending
	// the entire request takes longer than SOCKET_TIMEOUT_DEFAULT seconds.
	//
	// As a workaround this interceptor writes the request body in parts,
	// resulting in Okio's timeout being applied separately for each part.
	private static Interceptor sStreamingRequestInterceptor = new Interceptor() {
		@Override
		public Response intercept(Chain chain) throws IOException {
			final Request originalRequest = chain.request();

			if (originalRequest.body() == null) {
				return chain.proceed(originalRequest);
			}

			final RequestBody originalBody = originalRequest.body();
			RequestBody streamingRequestBody = new RequestBody() {
				@Override
				public MediaType contentType() {
					return originalBody.contentType();
				}

				@Override
				public long contentLength() throws IOException {
					return originalBody.contentLength();
				}

				@Override
				public void writeTo(@NonNull final BufferedSink sink) throws IOException {
					Sink streamingSink = new ForwardingSink(sink) {
						@Override
						public void write(@NonNull Buffer source, long byteCount) throws IOException {
							long written = 0L;

							while (written < byteCount) {
								long writeLength = Math.min(SEND_WINDOW_SIZE_BYTES, byteCount - written);

								sink.write(source.readByteArray(writeLength));
								written += writeLength;
							}
						}
					};

					BufferedSink bufferedSink = Okio.buffer(streamingSink);
					originalBody.writeTo(bufferedSink);
					bufferedSink.emit();
				}
			};

			return chain.proceed(originalRequest.newBuilder()
					.method(originalRequest.method(), streamingRequestBody)
					.build());
		}
	};

	private static CookieJar sCookieJar = new CookieJar() {
		private final HashMap<String, List<Cookie>> cookieStore = new HashMap<>();

		@Override
		public void saveFromResponse(HttpUrl url, @NonNull List<Cookie> cookies) {
			cookieStore.put(url.host(), cookies); // automatically keep ALL cookies
		}

		@Override
		public List<Cookie> loadForRequest(HttpUrl url) {
			List<Cookie> cookies = cookieStore.get(url.host());
			return cookies != null ? cookies : new ArrayList<Cookie>();
		}
	};

	public static OkHttpClient getOkHttpClient() {
		if (sOkHttpClient == null) {
			Dispatcher dispatcher = new Dispatcher();
			dispatcher.setMaxRequests(SIMULTANEOUS_CONNECTIONS);
			sOkHttpClient = new OkHttpClient().newBuilder()
					.connectTimeout(SOCKET_TIMEOUT_DEFAULT, TimeUnit.SECONDS)
					.readTimeout(SOCKET_TIMEOUT_DEFAULT, TimeUnit.SECONDS)
					.writeTimeout(SOCKET_TIMEOUT_DEFAULT, TimeUnit.SECONDS)
					.socketFactory(new CustomSocketFactory(null))
					.addInterceptor(sStreamingRequestInterceptor)
					.dispatcher(dispatcher)
					.cache(CACHE)
					.cookieJar(sCookieJar)
					.build();
		}
		return sOkHttpClient;
	}

	public static OkHttpClient getCustomTimeoutOkHttpClient(int timeout) {
		OkHttpClient client = getOkHttpClient();
		return client.newBuilder()
				.connectTimeout(SOCKET_TIMEOUT_SHORT, TimeUnit.SECONDS)
				.readTimeout(SOCKET_TIMEOUT_SHORT, TimeUnit.SECONDS)
				.writeTimeout(timeout, TimeUnit.SECONDS)
				.cache(CACHE)
				.cookieJar(sCookieJar)
				.build(); // note: no caching
	}
}

