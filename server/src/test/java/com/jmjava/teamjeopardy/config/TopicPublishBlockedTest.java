package com.jmjava.teamjeopardy.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmjava.teamjeopardy.game.GameSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
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
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "team-jeopardy.persistence.path=${java.io.tmpdir}/team-jeopardy-topic-publish-it.db"
)
class TopicPublishBlockedTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void aClientCannotPublishOntoTheLiveBoardTopic() throws Exception {
        JsonNode created = post("/api/rooms", Map.of("hostName", "Pat", "title", "Publish"));
        String roomId = created.path("snapshot").path("roomId").asText();
        String code = created.path("snapshot").path("code").asText();

        BlockingQueue<GameSnapshot> frames = new LinkedBlockingQueue<>();
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        List<Transport> transports = List.of(new RestTemplateXhrTransport());
        WebSocketStompClient client = new WebSocketStompClient(new SockJsClient(transports));
        MappingJackson2MessageConverter json = new MappingJackson2MessageConverter();
        json.setObjectMapper(objectMapper);
        client.setMessageConverter(json);
        StompSession session = client.connectAsync(
                "http://127.0.0.1:" + port + "/ws",
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(StompSession sess, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
                        errors.offer(exception);
                    }

                    @Override
                    public void handleTransportError(StompSession sess, Throwable exception) {
                        errors.offer(exception);
                    }
                }
        ).get(8, TimeUnit.SECONDS);
        session.subscribe("/topic/room." + roomId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return GameSnapshot.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                frames.offer((GameSnapshot) payload);
            }
        });
        Thread.sleep(250);

        StompHeaders forged = new StompHeaders();
        forged.setDestination("/topic/room." + roomId);
        forged.setContentType(MediaType.APPLICATION_JSON);
        session.send(forged, Map.of("title", "forged-board", "roomId", roomId));

        assertNull(frames.poll(1, TimeUnit.SECONDS), "forged board frame");
        assertNull(errors.poll(200, TimeUnit.MILLISECONDS), "forged board conversion");

        post("/api/rooms/join", Map.of("code", code, "displayName", "Alex", "teamName", "Blue"));
        GameSnapshot live = frames.poll(8, TimeUnit.SECONDS);
        assertTrue(live != null && "Publish".equals(live.title()), "real broadcast still arrives");
        client.stop();
    }

    private JsonNode post(String path, Map<String, ?> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(
                "http://localhost:" + port + path,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                String.class
        );
        assertTrue(response.getStatusCode().is2xxSuccessful(), response.getBody());
        return objectMapper.readTree(response.getBody());
    }
}
