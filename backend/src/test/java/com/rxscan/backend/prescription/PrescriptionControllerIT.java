package com.rxscan.backend.prescription;

import com.jayway.jsonpath.JsonPath;
import com.rxscan.backend.ConsumerApiTestBase;
import org.junit.jupiter.api.Test;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PrescriptionControllerIT extends ConsumerApiTestBase {

    static final String MEDS = "{\"payload\":{\"schema\":1,\"meds\":[{\"name\":\"Augmentin 625\",\"strength\":\"625mg\",\"slots\":[\"morning\",\"night\"],\"mealTiming\":\"after_food\",\"durationDays\":5,\"prn\":false}],\"confirmedAt\":\"2026-07-23T10:30:00+05:30\"}}";

    @Test
    void saveEditSyncRoundTrip() throws Exception {
        String token = signIn("9876500005");

        String created = mvc.perform(post("/v1/prescriptions").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(MEDS))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rx_id").exists())
                .andExpect(jsonPath("$.updated_at").exists())
                .andReturn().getResponse().getContentAsString();
        String rxId = JsonPath.read(created, "$.rx_id");

        mvc.perform(patch("/v1/prescriptions/" + rxId).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"payload\":{\"schema\":1,\"meds\":[],\"confirmedAt\":\"2026-07-23T11:00:00+05:30\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated_at").exists());

        mvc.perform(get("/v1/prescriptions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prescriptions.length()").value(1))
                .andExpect(jsonPath("$.prescriptions[0].rx_id").value(rxId))
                .andExpect(jsonPath("$.prescriptions[0].payload.schema").value(1));

        // since= far in the future filters everything out.
        mvc.perform(get("/v1/prescriptions").param("since", "2099-01-01T00:00:00Z")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.prescriptions.length()").value(0));
    }

    @Test
    void cannotTouchAnotherUsersPrescription() throws Exception {
        String alice = signIn("9876500006");
        String bob = signIn("9876500007");
        String created = mvc.perform(post("/v1/prescriptions").header("Authorization", "Bearer " + alice)
                        .contentType(APPLICATION_JSON).content(MEDS))
                .andReturn().getResponse().getContentAsString();
        String rxId = JsonPath.read(created, "$.rx_id");

        mvc.perform(patch("/v1/prescriptions/" + rxId).header("Authorization", "Bearer " + bob)
                        .contentType(APPLICATION_JSON).content(MEDS))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        mvc.perform(get("/v1/prescriptions").header("Authorization", "Bearer " + bob))
                .andExpect(jsonPath("$.prescriptions.length()").value(0));
    }
}
