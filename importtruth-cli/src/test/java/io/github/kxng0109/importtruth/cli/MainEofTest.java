package io.github.kxng0109.importtruth.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the end of the client's input is noticed, so a client
 * that closes the pipe gets the server stopped.
 */
@DisplayName("End of input")
final class MainEofTest {

	@Test
	@DisplayName("counts down when the client closes the pipe")
	void countsDownOnEndOfInput() throws Exception {
		CountDownLatch stop = new CountDownLatch(1);
		Main.EofAwareInputStream stream = new Main.EofAwareInputStream(
				new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), stop);

		byte[] buffer = new byte[4];
		int bulk = stream.read(buffer, 0, 4);
		int end = stream.read();

		assertThat(bulk).as("bulk read").isEqualTo(2);
		assertThat(end).as("end of stream").isEqualTo(-1);
		assertThat(stop.await(5, TimeUnit.SECONDS)).as("server stop signalled").isTrue();
	}

	@Test
	@DisplayName("signals on the bulk read that reaches the end")
	void signalsOnBulkRead() throws Exception {
		CountDownLatch stop = new CountDownLatch(1);
		Main.EofAwareInputStream stream = new Main.EofAwareInputStream(
				new ByteArrayInputStream(new byte[0]), stop);

		int count = stream.read(new byte[4], 0, 4);

		assertThat(count).as("nothing to read").isEqualTo(-1);
		assertThat(stop.await(5, TimeUnit.SECONDS)).as("server stop signalled").isTrue();
	}

	@Test
	@DisplayName("does not signal while the pipe is open")
	void doesNotSignalWhileOpen() throws Exception {
		CountDownLatch stop = new CountDownLatch(1);
		Main.EofAwareInputStream stream = new Main.EofAwareInputStream(
				new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), stop);

		stream.read();

		assertThat(stop.getCount()).as("still running").isEqualTo(1L);
	}

	@Test
	@DisplayName("propagates a read failure")
	void propagatesReadFailure() {
		CountDownLatch stop = new CountDownLatch(1);
		Main.EofAwareInputStream stream = new Main.EofAwareInputStream(new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("broken pipe");
			}
		}, stop);

		assertThatThrownBy(stream::read)
				.as("read failure surfaced")
				.isInstanceOf(IOException.class);
	}
}
