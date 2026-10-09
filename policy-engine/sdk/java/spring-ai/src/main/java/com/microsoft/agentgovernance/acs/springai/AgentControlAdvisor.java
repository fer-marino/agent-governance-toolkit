// Copyright (c) Microsoft Corporation.
// Licensed under the MIT License.

package com.microsoft.agentgovernance.acs.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.agentgovernance.acs.AgentControl;
import com.microsoft.agentgovernance.acs.AgentControlBlockedException;
import com.microsoft.agentgovernance.acs.Decision;
import com.microsoft.agentgovernance.acs.InterventionPoint;
import com.microsoft.agentgovernance.acs.InterventionPointResult;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.Ordered;

/**
 * A Spring AI chat client advisor that asks the Agent Control Specification engine about the model call: {@code pre_model_call} sees the
 * request ({@code {"messages":[{"role":..,"content":..}]}}) before the model does and {@code post_model_call} sees the answer
 * ({@code {"content":..}}) before the caller does.
 *
 * <p>Only {@code allow} lets the call through. A {@code deny} (also an approval that is not granted), an engine failure, and a {@code transform}
 * all block it with an {@link AgentControlBlockedException}: this advisor cannot rewrite a Spring AI request or response faithfully, and running
 * the call on a value the policy wanted changed would fail open. Use the SDK's {@code runModel} directly if you need transforms.
 *
 * <p>Tool calls run inside the model call and are not seen by this advisor; guard them with {@link GuardedToolCallback}.
 */
public final class AgentControlAdvisor implements CallAdvisor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentControl control;
    private final int order;

    public AgentControlAdvisor(AgentControl control) {
        this(control, Ordered.LOWEST_PRECEDENCE - 100);
    }

    public AgentControlAdvisor(AgentControl control, int order) {
        this.control = Objects.requireNonNull(control, "control");
        this.order = order;
    }

    @Override
    public String getName() {
        return "AgentControlAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        requireAllow(InterventionPoint.PRE_MODEL_CALL, control.evaluatePreModelCall(requestJson(request), AgentControl.Options.defaults()));
        ChatClientResponse response = chain.nextCall(request);
        requireAllow(InterventionPoint.POST_MODEL_CALL, control.evaluatePostModelCall(responseJson(response), AgentControl.Options.defaults()));
        return response;
    }

    static JsonNode requestJson(ChatClientRequest request) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode messages = root.putArray("messages");
        for (Message message : request.prompt().getInstructions()) {
            ObjectNode item = messages.addObject();
            item.put("role", message.getMessageType().getValue());
            item.put("content", message.getText() == null ? "" : message.getText());
        }
        return root;
    }

    static JsonNode responseJson(ChatClientResponse response) {
        ObjectNode root = JSON.createObjectNode();
        String content = response == null || response.chatResponse() == null || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null ? null : response.chatResponse().getResult().getOutput().getText();
        root.put("content", content == null ? "" : content);
        return root;
    }

    private static void requireAllow(InterventionPoint point, InterventionPointResult result) {
        Decision decision = result.verdict().decision();
        if (decision != Decision.ALLOW && decision != Decision.WARN) {
            throw new AgentControlBlockedException(point, result);
        }
    }
}
