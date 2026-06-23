package autismclient.util;

import com.mojang.authlib.Environment;
import com.mojang.util.UndashedUuid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import de.florianreuth.waybackauthlib.InvalidCredentialsException;
import de.florianreuth.waybackauthlib.WaybackAuthLib;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;

import java.util.Optional;
import java.util.Objects;

public class AutismAccount {
    private static final Environment ALTENING_ENVIRONMENT = new Environment("http://sessionserver.thealtening.com", "http://authserver.thealtening.com", "https://api.mojang.com", "The Altening");
    private static final String ALTENING_PASSWORD = "Meteor on Crack!";

    public AutismAccountType type = AutismAccountType.Cracked;
    public String label = "";
    public String token = "";

    public String sessionToken = "";
    public String username = "";
    public String uuid = "";
    private transient String lastError = "";
    private transient WaybackAuthLib alteningAuth;
    private transient String alteningAuthToken = "";

    public AutismAccount() {
    }

    public AutismAccount(Tag tag) {
        if (tag instanceof CompoundTag compoundTag) fromTag(compoundTag);
    }

    public String displayName() {
        if (username != null && !username.isBlank()) return username;
        return label == null ? "" : label;
    }

    public boolean fetchInfo() {
        lastError = "";
        return switch (type) {
            case Cracked -> fetchCracked();
            case Session -> fetchSession();
            case Microsoft -> fetchMicrosoft();
            case TheAltening -> fetchTheAltening();
        };
    }

    public boolean login() {
        lastError = "";
        if ((username == null || username.isBlank()) && !fetchInfo()) return false;
        return switch (type) {
            case Cracked -> {
                boolean ok = AutismAccountSessionSwitcher.setSession(new User(username, UUIDUtil.createOfflinePlayerUUID(username), "", Optional.empty(), Optional.empty()));
                if (!ok) lastError = AutismAccountSessionSwitcher.lastError();
                yield ok;
            }
            case Session, Microsoft -> {
                if (token == null || token.isBlank() || uuid == null || uuid.isBlank()) {
                    lastError = "missing token or uuid";
                    yield false;
                }
                boolean ok = AutismAccountSessionSwitcher.setSession(new User(username, UndashedUuid.fromStringLenient(uuid), token, Optional.empty(), Optional.empty()));
                if (!ok) lastError = AutismAccountSessionSwitcher.lastError();
                yield ok;
            }
            case TheAltening -> loginTheAltening();
        };
    }

    private boolean fetchCracked() {
        if (label == null || label.isBlank()) {
            lastError = "missing username";
            return false;
        }
        username = label.trim();
        uuid = UUIDUtil.createOfflinePlayerUUID(username).toString();
        return true;
    }

    private boolean fetchSession() {
        if (token == null || token.isBlank()) {
            lastError = "missing access token";
            return false;
        }
        try {
            var profile = AutismHttp.getJson("https://api.minecraftservices.com/minecraft/profile", token);
            if (profile == null || !profile.has("id") || !profile.has("name")) {
                lastError = "profile response missing id/name";
                return false;
            }
            uuid = profile.get("id").getAsString();
            username = profile.get("name").getAsString();
            return true;
        } catch (Exception e) {
            lastError = shortError(e);
            return false;
        }
    }

    private boolean fetchMicrosoft() {
        if (label == null || label.isBlank()) {
            lastError = "missing refresh token";
            return false;
        }
        AutismMicrosoftLogin.LoginData data = AutismMicrosoftLogin.login(label);
        if (!data.isGood()) {
            lastError = "Microsoft login failed";
            return false;
        }
        token = data.mcToken;
        uuid = data.uuid;
        username = data.username;
        label = data.newRefreshToken;
        return true;
    }

    private boolean fetchTheAltening() {
        if (token == null || token.isBlank()) {
            lastError = "missing TheAltening token";
            return false;
        }
        try {
            WaybackAuthLib auth = createTheAlteningAuth();
            auth.logIn();
            username = auth.getCurrentProfile().name();
            uuid = auth.getCurrentProfile().id().toString();
            sessionToken = auth.getAccessToken() == null ? "" : auth.getAccessToken();
            alteningAuth = auth;
            alteningAuthToken = token;
            return true;
        } catch (InvalidCredentialsException e) {
            clearTheAlteningAuth();
            lastError = shortError(e);
            AutismClientMessaging.sendPrefixed("Invalid TheAltening credentials.");
            return false;
        } catch (Exception e) {
            clearTheAlteningAuth();
            lastError = shortError(e);
            AutismClientMessaging.sendPrefixed("Failed to fetch TheAltening account info.");
            return false;
        }
    }

    private boolean loginTheAltening() {
        if (token == null || token.isBlank()) {
            lastError = "missing TheAltening token";
            return false;
        }
        try {
            boolean cached = validCachedTheAlteningAuth();
            WaybackAuthLib auth = cached ? alteningAuth : createTheAlteningAuth();
            if (!cached) {
                auth.logIn();
                alteningAuth = auth;
                alteningAuthToken = token;
            }
            username = auth.getCurrentProfile().name();
            uuid = auth.getCurrentProfile().id().toString();
            sessionToken = auth.getAccessToken() == null ? "" : auth.getAccessToken();
            boolean ok = AutismAccountSessionSwitcher.setSession(
                new User(auth.getCurrentProfile().name(), auth.getCurrentProfile().id(), sessionToken, Optional.empty(), Optional.empty()),
                new YggdrasilAuthenticationService(Minecraft.getInstance().getProxy(), ALTENING_ENVIRONMENT)
            );
            if (!ok) lastError = AutismAccountSessionSwitcher.lastError();
            return ok;
        } catch (Exception e) {
            clearTheAlteningAuth();
            lastError = shortError(e);
            AutismClientMessaging.sendPrefixed("Failed to login with TheAltening.");
            return false;
        }
    }

    private WaybackAuthLib createTheAlteningAuth() {
        WaybackAuthLib auth = new WaybackAuthLib(ALTENING_ENVIRONMENT.servicesHost());
        auth.setUsername(token);
        auth.setPassword(ALTENING_PASSWORD);
        return auth;
    }

    private boolean validCachedTheAlteningAuth() {
        return alteningAuth != null
            && token != null
            && !token.isBlank()
            && token.equals(alteningAuthToken)
            && alteningAuth.getCurrentProfile() != null
            && alteningAuth.getAccessToken() != null
            && !alteningAuth.getAccessToken().isBlank();
    }

    private void clearTheAlteningAuth() {
        alteningAuth = null;
        alteningAuthToken = "";
    }

    public String lastError() {
        return lastError == null ? "" : lastError;
    }

    public String failureSuffix() {
        String error = lastError();
        return error.isBlank() ? "" : " (" + error + ")";
    }

    private static String shortError(Throwable error) {
        if (error == null) return "unknown error";
        String name = error.getClass().getSimpleName();
        String message = error.getMessage();
        if (message == null || message.isBlank()) return name;
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (message.length() > 120) message = message.substring(0, 117) + "...";
        return name + ": " + message;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", type == null ? AutismAccountType.Cracked.name() : type.name());
        tag.putString("label", label == null ? "" : label);
        tag.putString("token", token == null ? "" : token);
        tag.putString("sessionToken", sessionToken == null ? "" : sessionToken);
        tag.putString("username", username == null ? "" : username);
        tag.putString("uuid", uuid == null ? "" : uuid);
        return tag;
    }

    public AutismAccount fromTag(CompoundTag tag) {
        String typeName = tag.getStringOr("type", AutismAccountType.Cracked.name());
        try {
            type = AutismAccountType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            type = AutismAccountType.Cracked;
        }
        label = tag.getStringOr("label", tag.getStringOr("name", ""));
        token = tag.getStringOr("token", "");
        sessionToken = tag.getStringOr("sessionToken", "");
        username = tag.getStringOr("username", "");
        uuid = tag.getStringOr("uuid", "");
        return this;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof AutismAccount account)) return false;
        if (type != account.type) return false;
        return Objects.equals(identityKey(), account.identityKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, identityKey());
    }

    private String identityKey() {
        return switch (type) {
            case Cracked -> username != null && !username.isBlank() ? username : label;
            case Session, TheAltening -> token != null && !token.isBlank() ? token : label;
            case Microsoft -> label != null && !label.isBlank() ? label : username;
        };
    }
}
