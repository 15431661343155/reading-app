package com.example.readingapp.exception;

public class BookAlreadyInShelfException extends RuntimeException {
    public BookAlreadyInShelfException(String message) {
        super(message);
    }
}