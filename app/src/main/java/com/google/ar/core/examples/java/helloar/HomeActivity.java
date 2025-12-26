package com.google.ar.core.examples.java.helloar;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class HomeActivity extends AppCompatActivity {

    private Spinner spinnerAircraft;
    private AircraftConfig.AircraftType selectedAircraftType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        spinnerAircraft = findViewById(R.id.spinnerAircraft);
        Button btnUpload = findViewById(R.id.btnUpload);
        Button btnARView = findViewById(R.id.btnARView);

        // Setup aircraft type spinner
        setupAircraftSpinner();

        btnUpload.setOnClickListener(v -> {
            // TODO: Implement file picker for loading plan
            Toast.makeText(this, "Loading plan upload not yet implemented", Toast.LENGTH_SHORT).show();
        });

        btnARView.setOnClickListener(v -> {
            selectedAircraftType = AircraftConfig.AircraftType.values()[spinnerAircraft.getSelectedItemPosition()];
            
            Intent intent = new Intent(this, HelloArActivity.class);
            intent.putExtra("AIRCRAFT_TYPE", selectedAircraftType.name());
            startActivity(intent);
        });
    }

    private void setupAircraftSpinner() {
        AircraftConfig.AircraftType[] types = AircraftConfig.AircraftType.values();
        String[] displayNames = new String[types.length];
        
        for (int i = 0; i < types.length; i++) {
            displayNames[i] = types[i].getDisplayName();
        }
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            displayNames
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAircraft.setAdapter(adapter);
    }
}
