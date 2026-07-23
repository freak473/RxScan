package com.rxscan.backend.consent;

import com.rxscan.backend.auth.CurrentUser;
import com.rxscan.backend.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Post-login consent upload — e.g. `notify` from the notif-perm screen (login is late in the flow). */
@RestController
@RequestMapping("/v1/me/consents")
public class ConsentController {

    record Body(List<ConsentDto> consents) {}

    private final CurrentUser currentUser;
    private final ConsentRepository consents;

    public ConsentController(CurrentUser currentUser, ConsentRepository consents) {
        this.currentUser = currentUser;
        this.consents = consents;
    }

    @PutMapping
    ResponseEntity<Void> put(HttpServletRequest req, @RequestBody Body body) {
        UserRepository.UserRow user = currentUser.require(req);
        for (ConsentDto c : body.consents() == null ? List.<ConsentDto>of() : body.consents()) {
            consents.insert(user.userId(), c.purpose(), c.granted(), c.grantedAt());
        }
        return ResponseEntity.noContent().build();
    }
}
