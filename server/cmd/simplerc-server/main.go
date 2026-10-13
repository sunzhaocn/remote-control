package main

import (
	"context"
	"log"
	"net/http"
	"simplerc/server/internal/config"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
)
func main(){
	c,err:=config.Load(); if err!=nil{log.Fatal(err)}
	db,err:=pgxpool.New(context.Background(),c.DatabaseURL); if err!=nil{log.Fatal(err)}; defer db.Close()
	ro,err:=redis.ParseURL(c.RedisURL); if err!=nil{log.Fatal(err)}; rdb:=redis.NewClient(ro); defer rdb.Close()
	mux:=http.NewServeMux(); mux.HandleFunc("GET /healthz",func(w http.ResponseWriter,r *http.Request){ if db.Ping(r.Context())!=nil||rdb.Ping(r.Context()).Err()!=nil{http.Error(w,"unavailable",503);return}; w.WriteHeader(204) })
	log.Printf("SimpleRC signaling listening on %s",c.ListenAddr); log.Fatal(http.ListenAndServe(c.ListenAddr,mux))
}
