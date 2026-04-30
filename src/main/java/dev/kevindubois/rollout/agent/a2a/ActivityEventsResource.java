package dev.kevindubois.rollout.agent.a2a;

import dev.kevindubois.rollout.agent.model.ActivityEvent;
import dev.kevindubois.rollout.agent.model.ActivityEventStore;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.List;

@Path("/a2a/events")
public class ActivityEventsResource {

    @Inject
    ActivityEventStore eventStore;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<ActivityEvent> getEvents(@QueryParam("since") Long sinceId) {
        if (sinceId != null) {
            return eventStore.getEventsSince(sinceId);
        }
        return eventStore.getEvents();
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<ActivityEvent> streamEvents() {
        return eventStore.stream();
    }
}
