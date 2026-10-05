package rngtrader.core;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

/** Historical packets establish the rejected wall-time inputs, not server processing ticks. */
public class LegacyTimingTraceTest {
    private JsonArray rows(String name) throws Exception {
        try(Reader r=new InputStreamReader(getClass().getResourceAsStream("/timing-failures/"+name+".json"),StandardCharsets.UTF_8)) {
            return new JsonParser().parse(r).getAsJsonArray();
        }
    }
    @Test public void calibrationRejectedAnIntervalWithinTheCoarseAlgorithmRange() throws Exception {
        long previous=-1,current=-1;String reason=null;
        for(JsonElement row:rows("calibration")) {
            JsonObject r=row.getAsJsonObject();String event=r.get("event").getAsString();
            if(event.equals("sound")){previous=current;current=r.get("received").getAsLong();}
            if(event.equals("error")){reason=r.get("reason").getAsString();break;}
        }
        assertEquals(24,Math.round((current-previous)/50_000_000.0));
        assertTrue(reason.startsWith("Calibration probe timing"));
    }
    @Test public void receiptLatencyWasRoundedIntoTheReopenLimit() throws Exception {
        Long close=null;double elapsed=0;String reason=null;
        for(JsonElement row:rows("reopen")) {
            JsonObject r=row.getAsJsonObject();String event=r.get("event").getAsString();
            if(event.equals("close_for_refresh"))close=r.get("closeTime").getAsLong();
            if(event.equals("offers")&&close!=null)elapsed=(r.get("received").getAsLong()-close)/50_000_000.0;
            if(event.equals("error")){reason=r.get("reason").getAsString();break;}
        }
        assertTrue(elapsed>44.5&&elapsed<45);assertTrue(reason.startsWith("Reopen exceeded"));
    }
    @Test public void secondFailureOccurredAfterAnActualAppend() throws Exception {
        int successful=0;JsonObject offer=null;String reason=null;
        for(JsonElement row:rows("refresh")) {
            JsonObject r=row.getAsJsonObject();String event=r.get("event").getAsString();
            if(event.equals("refresh"))successful++;
            if(event.equals("offers")){JsonArray a=r.getAsJsonArray("offers");offer=a.get(a.size()-1).getAsJsonObject();}
            if(event.equals("error")){reason=r.get("reason").getAsString();break;}
        }
        assertEquals(1,successful);assertEquals("IRON_AXE",offer.get("kind").getAsString());
        assertEquals(7,offer.get("price").getAsInt());assertEquals("Refresh excluded all stock/timing models",reason);
    }
}
