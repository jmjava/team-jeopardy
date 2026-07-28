package com.jmjava.teamjeopardy.quiz.enrich;

import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.quiz.Category;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "team-jeopardy.openai.enabled", havingValue = "false", matchIfMissing = true)
public class PassthroughQuestionEnricher implements QuestionEnricher {

    @Override
    public List<Category> enrich(CodeGraph graph, List<Category> categories) {
        return categories;
    }
}
