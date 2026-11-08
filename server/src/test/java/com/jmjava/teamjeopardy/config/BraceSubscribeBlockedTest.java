package com.jmjava.teamjeopardy.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmjava.teamjeopardy.api.GameBroadcaster;
import com.jmjava.teamjeopardy.game.GameRoomService;
import com.jmjava.teamjeopardy.game.GameSnapshot;
import com.jmjava.teamjeopardy.quiz.Board;
import com.jmjava.teamjeopardy.quiz.Category;
import com.jmjava.teamjeopardy.quiz.Clue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.RestTemplateXhrTransport;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code *} and {@code ?} are already refused. A URI-template subscribe is a
 * different pattern: the broker matches {@code /topic/{feed}} to the host topic.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "team-jeopardy.persistence.path=${java.io.tmpdir}/team-jeopardy-brace-subscribe-it.db"
)
class BraceSubscribeBlockedTest {

    private static final String SECRET = "What is brace-secret?";

    @LocalServerPort
    int port;

    @Autowired
    GameRoomService gameRoomService;

    @Autowired
    GameBroadcaster broadcaster;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void aBracePatternSubscribeDoesNotReceiveTheHostFeed() throws Exception {
        var created = gameRoomService.createRoom("Pat", "Brace");
        String roomId = created.snapshot().roomId();
        gameRoomService.installBoard(roomId, created.hostPlayerId(), board());

        BlockingQueue<GameSnapshot> pattern = new LinkedBlockingQueue<>();
        BlockingQueue<GameSnapshot> exact = new LinkedBlockingQueue<>();
        List<Transport> transports = List.of(new RestTemplateXhrTransport());
        WebSocketStompClient client = new WebSocketStompClient(new SockJsClient(transports));
        MappingJackson2MessageConverter json = new MappingJackson2MessageConverter();
        json.setObjectMapper(objectMapper);
        client.setMessageConverter(json);
        StompSession session = client.connectAsync(
                "http://127.0.0.1:" + port + "/ws",
                new StompSessionHandlerAdapter() {}
        ).get(8, TimeUnit.SECONDS);
        session.subscribe("/topic/{feed}", handler(pattern));
        session.subscribe("/topic/room." + roomId, handler(exact));
        Thread.sleep(250);

        broadcaster.broadcast(roomId);

        assertNull(pattern.poll(1, TimeUnit.SECONDS), "brace pattern received a room topic");
        GameSnapshot live = exact.poll(8, TimeUnit.SECONDS);
        assertNotNull(live, "exact public subscribe still receives the board");
        assertNull(pattern.poll(300, TimeUnit.MILLISECONDS), "host feed followed the public frame");
        client.stop();
    }

    private static StompFrameHandler handler(BlockingQueue<GameSnapshot> frames) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return GameSnapshot.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                frames.offer((GameSnapshot) payload);
            }
        };
    }

    private static Board board() {
        return new Board(
                "Brace",
                "/tmp",
                List.of(new Category(
                        "cat",
                        "TEST",
                        List.of(new Clue("c1", 200, "Name the guard", SECRET, null, null, false))
                )),
                new Board.GraphDigest(1, 1, 1, 1, 1)
        );
    }
}
