package org.mitallast.queue.action.queues.create;

import org.mitallast.queue.action.ActionRequest;
import org.mitallast.queue.action.ActionRequestValidationException;
import org.mitallast.queue.common.settings.Settings;
import org.mitallast.queue.queue.QueueType;

import static org.mitallast.queue.action.ValidateActions.addValidationError;

public class CreateQueueRequest extends ActionRequest {
    private String queue;
    private QueueType type;
    private Settings settings;

    public CreateQueueRequest(String queue) {
        this.queue = queue;
    }

    public QueueType getType() {
        return type;
    }

    public void setType(QueueType type) {
        this.type = type;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public Settings getSettings() {
        return settings;
    }

    public void setSettings(Settings settings) {
        this.settings = settings;
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (queue == null) {
            validationException = addValidationError("queue is missing", null);
        }
        if (type == null) {
            validationException = addValidationError("queue type is missing", null);
        }
        if (settings == null) {
            validationException = addValidationError("settings is missing", validationException);
        }
        return validationException;
    }


}
