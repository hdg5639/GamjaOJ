package dev.gamjaoj;

class AccountException extends RuntimeException {
    final int status;
    AccountException(int status, String message) {
        super(message);
        this.status = status;
    }
}
