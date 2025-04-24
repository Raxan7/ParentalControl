package com.example.parentalcontrol;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

public class LoginFragment extends Fragment {
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_login, container, false);

        EditText username = view.findViewById(R.id.username);
        EditText password = view.findViewById(R.id.password);
        Button loginButton = view.findViewById(R.id.login_button);

        loginButton.setOnClickListener(v -> {
            String user = username.getText().toString();
            String pass = password.getText().toString();

            if (!user.isEmpty() && !pass.isEmpty()) {
                ((MainActivity)requireActivity()).attemptLogin(user, pass);
            } else {
                Toast.makeText(getContext(),
                        "Please enter username and password",
                        Toast.LENGTH_SHORT).show();
            }
        });

        return view;
    }
}
