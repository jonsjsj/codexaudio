# audex-align — forced-alignment service (Tier-3 immersion reading).
# CPU by default (faster-whisper int8). For the GPU (ventans 4070 Ti):
# install nvidia-container-toolkit on the host, then recreate the container
# with the nvidia runtime + ALIGN_DEVICE=cuda (see README).
FROM python:3.12-slim

# ffmpeg: the decode path shells out to it (C-speed, ~250x realtime) to split the
# audiobook into WAV chunks — far faster than the PyAV Python fallback.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# Whisper model cache lives on the data volume so pulls survive recreation.
ENV HF_HOME=/data/hf \
    ALIGN_DEVICE=cpu \
    ALIGN_MODEL=small \
    ALIGN_DATA=/data

VOLUME /data
EXPOSE 8585
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8585"]
