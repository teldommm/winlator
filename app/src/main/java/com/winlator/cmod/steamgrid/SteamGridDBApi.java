package com.winlator.cmod.steamgrid;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface SteamGridDBApi {

    @GET("search/autocomplete/{term}")
    Call<SteamGridSearchResponse> searchGame(
            @Header("Authorization") String authHeader,
            @Path("term") String searchTerm
    );

    // styles / dimensions / types are optional: Retrofit leaves out a null query parameter.
    // dimensions and styles accept comma separated lists ("660x930,600x900,342x482").
    @GET("grids/game/{gameId}")
    Call<SteamGridGridsResponse> getGridsByGameId(
            @Header("Authorization") String authToken,
            @Path("gameId") int gameId,
            @Query("styles") String styles,           // Example: "alternate"
            @Query("dimensions") String dimensions,   // Example: "600x900"
            @Query("types") String types              // Example: "static"
    );

    // Same as above, addressed by Steam App ID instead of the SteamGridDB game id.
    @GET("grids/steam/{appId}")
    Call<SteamGridGridsResponse> getGridsBySteamAppId(
            @Header("Authorization") String authToken,
            @Path("appId") long appId,
            @Query("styles") String styles,
            @Query("dimensions") String dimensions,
            @Query("types") String types
    );
}
