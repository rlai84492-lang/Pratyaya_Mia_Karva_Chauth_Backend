package org.example.karvachauth.service;

public interface AuthService {

    /** Naya dashboard user. Galat input / username pehle se hai -> IllegalArgumentException. */
    void signup(String username, String password, String role);

    /** Login successful hone par time save. */
    void recordLogin(String username);
}