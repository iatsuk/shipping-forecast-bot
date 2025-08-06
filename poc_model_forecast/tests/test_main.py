def test_main_orchestrates(tmp_path, poc, monkeypatch):
    calls = []
    def fake_fetch(lat, lon, models):
        calls.append('fetch')
        return {'m1': [{"time": "t0", "wind": 1, "gust": 2, "direction": 90}]}
    def fake_map(lat, lon, model, output_path, size=11):
        calls.append('map')
        return output_path
    def fake_table(forecasts, output_path):
        calls.append('table')
        return output_path
    monkeypatch.setattr(poc, 'fetch_forecast', fake_fetch)
    monkeypatch.setattr(poc, 'create_wind_map', fake_map)
    monkeypatch.setattr(poc, 'render_forecast_table', fake_table)
    result = poc.main(0.0, 0.0, models=['m1'],
                      map_path=str(tmp_path/'map.png'),
                      table_path=str(tmp_path/'table.png'))
    assert calls == ['fetch', 'map', 'table']
    assert result['map'].endswith('map.png')
    assert result['table'].endswith('table.png')
