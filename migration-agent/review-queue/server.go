package main

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
)

type Server struct {
	items []QueueItem
}

func NewServer(items []QueueItem) *Server {
	return &Server{items: items}
}

func (s *Server) handleQueue(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(s.items)
}

func (s *Server) handleQueueItem(w http.ResponseWriter, r *http.Request) {
	idStr := strings.TrimPrefix(r.URL.Path, "/queue/")
	id, err := strconv.Atoi(idStr)
	if err != nil {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	for _, item := range s.items {
		if item.ID == id {
			w.Header().Set("Content-Type", "application/json")
			json.NewEncoder(w).Encode(item)
			return
		}
	}
	http.NotFound(w, r)
}

func (s *Server) handleStats(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(computeStats(s.items))
}

func (s *Server) Mux() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/queue", s.handleQueue)
	mux.HandleFunc("/queue/", s.handleQueueItem)
	mux.HandleFunc("/stats", s.handleStats)
	return mux
}
